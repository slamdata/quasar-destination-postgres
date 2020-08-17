/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.plugin.postgres

import slamdata.Predef._

import quasar.api.push.OffsetKey
import quasar.api.{Column, ColumnType}
import quasar.connector.destination.ResultSink.UpsertSink
import quasar.connector.destination.{WriteMode => QWriteMode}
import quasar.connector.{DataEvent, IdBatch, MonadResourceErr, ResourceError}

import org.slf4s.Logging

import cats.data.{NonEmptyList, NonEmptyVector}
import cats.effect.concurrent.Ref
import cats.effect.syntax.bracket._
import cats.effect.{Effect, ExitCase, LiftIO, Timer}
import cats.implicits._

import doobie.free.connection.{rollback, setAutoCommit, unit}
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.{PFCI, PFCM, PHC}
import doobie.util.transactor.Strategy
import doobie.{ConnectionIO, FC, Fragment, Fragments, Transactor}

import fs2.{Chunk, Pipe, Stream}

import skolems.Forall

object CsvUpsertSink extends Logging {
  def apply[F[_]: Effect: MonadResourceErr](
    xa0: Transactor[F],
    writeMode: WriteMode)(
    implicit timer: Timer[F])
      : Forall[λ[α => UpsertSink.Args[F, ColumnType.Scalar, α] => Stream[F, OffsetKey.Actual[α]]]] = {

    val strategy = Strategy(setAutoCommit(false), unit, rollback, unit)
    val xa = Transactor.strategy.modify(xa0, _ => strategy)

    Forall[λ[α => UpsertSink.Args[F, ColumnType.Scalar, α] => Stream[F, OffsetKey.Actual[α]]]](
      run(xa, writeMode))
  }

  def run[F[_]: Effect: MonadResourceErr, I](
    xa: Transactor[F],
    writeMode: WriteMode)(
    args: UpsertSink.Args[F, ColumnType.Scalar, I])(
    implicit timer: Timer[F])
      : Stream[F, OffsetKey.Actual[I]] = {

    val columns: NonEmptyList[Column[ColumnType.Scalar]] =
      NonEmptyList(args.idColumn, args.otherColumns)

    val toConnectionIO = Effect.toIOK[F] andThen LiftIO.liftK[ConnectionIO]

    val table: F[Table] =
      tableFromPath(args.path) match {
        case Some(t) =>
          t.pure[F]

        case None =>
          MonadResourceErr[F].raiseError(ResourceError.notAResource(args.path))
      }


    def handleCreate(totalBytes: Ref[F, Long], records: Chunk[Byte]): ConnectionIO[Unit] =
      for {
        tbl <- toConnectionIO(table)

        colSpecs <- toConnectionIO(specifyColumnFragments[F](columns))

        indexColumn = Fragments.parentheses(Fragment.const(hygienicIdent(args.idColumn.name)))

        _ <- (args.writeMode, writeMode) match {
          case (QWriteMode.Replace, WriteMode.Create) =>
            createTable(log)(tbl, colSpecs) >> createIndex(log)(tbl, indexColumn)
          case (QWriteMode.Replace, WriteMode.Replace) =>
            dropTableIfExists(log)(tbl) >> createTable(log)(tbl, colSpecs) >> createIndex(log)(tbl, indexColumn)
          case (QWriteMode.Replace, WriteMode.Truncate) =>
            createTableIfNotExists(log)(tbl, colSpecs) >> truncateTable(log)(tbl) >> createIndex(log)(tbl, indexColumn)
          case (QWriteMode.Replace, WriteMode.Append) =>
            createTableIfNotExists(log)(tbl, colSpecs) >> createIndex(log)(tbl, indexColumn)
          case (QWriteMode.Append, _) =>
            ().pure[ConnectionIO]
        }

        cols = columns.map(c => hygienicIdent(c.name)).intercalate(", ")

        copyQuery =
          s"COPY ${hygienicIdent(tbl)} ($cols) FROM STDIN WITH (FORMAT csv, HEADER FALSE, ENCODING 'UTF8')"

        data = records.toBytes

        copied = PFCM.copyIn(copyQuery).bracketCase(
          PFCM.embed(_, PFCI.writeToCopy(data.values, data.offset, data.length))) { (pgci, exitCase) =>
          PFCM.embed(pgci, exitCase match {
            case ExitCase.Completed => PFCI.endCopy.void
            case _ => PFCI.isActive.ifM(PFCI.cancelCopy, PFCI.unit)
          })
        }

        back <- PHC.pgGetCopyAPI(copied)

        _ <- toConnectionIO(recordChunks[F](totalBytes, log)(records))

      } yield back

    def handleDelete(recordIds: IdBatch): ConnectionIO[Unit] =
      if (recordIds.size === 0) {
        ().pure[ConnectionIO]
      } else {

        def preambleFragment(tbl: Table): Fragment =
          fr"DELETE FROM" ++
            Fragment.const(hygienicIdent(tbl)) ++
            fr"WHERE" ++
            Fragment.const(hygienicIdent(args.idColumn.name))

        def deleteFrom(preamble: Fragment, table: Table): ConnectionIO[Int] =
          recordIds match {
            case IdBatch.Strings(values, _) =>
              Fragments.in(preamble, NonEmptyVector.fromVectorUnsafe(values.toVector)) // trust size passed by quasar
                .updateWithLogHandler(logHandler(log))
                .run
            case IdBatch.Longs(values, _) =>
              Fragments.in(preamble, NonEmptyVector.fromVectorUnsafe(values.toVector))
                .updateWithLogHandler(logHandler(log))
                .run
            case IdBatch.Doubles(values, _) =>
              Fragments.in(preamble, NonEmptyVector.fromVectorUnsafe(values.toVector))
                .updateWithLogHandler(logHandler(log))
                .run
            case IdBatch.BigDecimals(values, _) =>
              Fragments.in(preamble, NonEmptyVector.fromVectorUnsafe(values.toVector))
                .updateWithLogHandler(logHandler(log))
                .run
        }

        for {
          tbl <- toConnectionIO(table)
          preamble = preambleFragment(tbl)
          _ <- deleteFrom(preamble, tbl)
        } yield ()
      }

    def handleCommit(offset: OffsetKey.Actual[I]): ConnectionIO[OffsetKey.Actual[I]] =
      FC.commit.as(offset)

    def eventHandler(totalBytes: Ref[F, Long])
        : Pipe[ConnectionIO, DataEvent[OffsetKey.Actual[I]], Option[OffsetKey.Actual[I]]] =
      _ evalMap {
        case DataEvent.Create(records) =>
          handleCreate(totalBytes, records).as(none[OffsetKey.Actual[I]])
        case DataEvent.Delete(recordIds) =>
          handleDelete(recordIds).as(none[OffsetKey.Actual[I]])
        case DataEvent.Commit(offset) =>
          handleCommit(offset).map(_.some)
      }

    Stream.eval(Ref[F].of(0L)) flatMap { tb =>
      val translated = args.input.translate(toConnectionIO)

      eventHandler(tb)(translated).unNone.transact(xa)
    }
  }
}