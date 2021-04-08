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

import quasar.api.{Column, ColumnType}
import quasar.api.push.OffsetKey
import quasar.api.resource.ResourcePath
import quasar.connector.{AppendEvent, DataEvent, MonadResourceErr}
import quasar.connector.destination.{WriteMode => QWriteMode, ResultSink}, ResultSink.{UpsertSink, AppendSink}
import quasar.connector.render.RenderConfig
import quasar.lib.jdbc.destination.{WriteMode => JWriteMode}

import cats.~>
import cats.data.NonEmptyList
import cats.effect.Effect
import cats.effect.concurrent.Ref
import cats.implicits._

import doobie._
import doobie.implicits._

import fs2.{Pipe, Stream}

import org.slf4s.Logger

import skolems.∀

object SinkBuilder {
  type Consume[F[_], Event[_], A] =
    Pipe[F, Event[OffsetKey.Actual[A]], OffsetKey.Actual[A]]

  def upsert[F[_]: Effect: MonadResourceErr](
      xa: Transactor[F],
      writeMode: JWriteMode,
      schema: Option[String],
      retry: ConnectionIO ~> ConnectionIO,
      logger: Logger)(
      args: UpsertSink.Args[ColumnType.Scalar])
      : (RenderConfig[Byte], ∀[Consume[F, DataEvent[Byte, *], *]]) = {
    val consume = ∀[Consume[F, DataEvent[Byte, *], *]](upsertPipe(
      xa,
      args.writeMode,
      writeMode,
      schema,
      args.path,
      Some(args.idColumn),
      args.columns,
      Some(args.idColumn),
      retry,
      logger))
    (PostgresCsvConfig, consume)
  }

  def append[F[_]: Effect: MonadResourceErr](
      xa: Transactor[F],
      writeMode: JWriteMode,
      schema: Option[String],
      retry: ConnectionIO ~> ConnectionIO,
      logger: Logger)(
      args: AppendSink.Args[ColumnType.Scalar])
      : (RenderConfig[Byte], ∀[Consume[F, AppendEvent[Byte, *], *]]) = {
    val consume = ∀[Consume[F, AppendEvent[Byte, *], *]](upsertPipe(
      xa,
      args.writeMode,
      writeMode,
      schema,
      args.path,
      args.pushColumns.primary,
      args.columns,
      None,
      retry,
      logger))
    (PostgresCsvConfig, consume)
  }

  private def upsertPipe[F[_]: Effect: MonadResourceErr, A](
      xa: Transactor[F],
      writeMode: QWriteMode,
      jwriteMode: JWriteMode,
      schema: Option[String],
      path: ResourcePath,
      idColumn: Option[Column[ColumnType.Scalar]],
      inputColumns: NonEmptyList[Column[ColumnType.Scalar]],
      filterColumn: Option[Column[ColumnType.Scalar]],
      retry: ConnectionIO ~> ConnectionIO,
      logger: Logger)
      : Pipe[F, DataEvent[Byte, OffsetKey.Actual[A]], OffsetKey.Actual[A]] = { events =>
/*
    val (actualId, actualColumns0) = idColumn match {
      case Some(c) => ensureIndexableIdColumn(c, inputColumns).leftMap(Some(_))
      case None => (None, inputColumns)
    }

    val (actualFilter, actualColumns) = filterColumn match {
      case Some(c) => ensureIndexableIdColumn(c, actualColumns0).leftMap(Some(_))
      case None => (None, actualColumns0)
    }
*/
    //val hyColumns = hygienicColumns(columns)

    def logEvents(event: DataEvent[Byte, _]): F[Unit] = event match {
      case DataEvent.Create(chunk) =>
        trace(logger)(s"Loading chunk with size: ${chunk.size}")
      case DataEvent.Delete(idBatch) =>
        trace(logger)(s"Deleting ${idBatch.size} records")
      case DataEvent.Commit(_) =>
        trace(logger)(s"Commit")
    }

    def handleEvents(
      refMode: Ref[ConnectionIO, QWriteMode],
      flow: TempTableFlow)
      : Pipe[ConnectionIO, DataEvent[Byte, OffsetKey.Actual[A]], Option[OffsetKey.Actual[A]]] = _ evalMap {

      case DataEvent.Create(chunk) =>
        flow.ingest(chunk) >>
        none[OffsetKey.Actual[A]].pure[ConnectionIO]

      case DataEvent.Delete(ids) =>
        none[OffsetKey.Actual[A]].pure[ConnectionIO]

      case DataEvent.Commit(offset) => refMode.get flatMap {
        case QWriteMode.Replace =>
          flow.replace >>
          refMode.set(QWriteMode.Append) >>
          offset.some.pure[ConnectionIO]
        case QWriteMode.Append =>
          flow.append >>
          offset.some.pure[ConnectionIO]
      }
    }

    def trace(logger: Logger)(msg: => String): F[Unit] =
      Effect[F].delay(logger.trace(msg))

    val result = for {
      flow <- Stream.resource(TempTableFlow(
        xa,
        logger,
        jwriteMode,
        path,
        schema,
        inputColumns,
        idColumn,
        filterColumn,
        retry))
      refMode <- Stream.eval(Ref.in[F, ConnectionIO, QWriteMode](writeMode))
      offset <- {
        events.evalTap(logEvents)
          .translate(toConnectionIO[F])
          .through(handleEvents(refMode, flow))
          .unNone
          .translate(λ[ConnectionIO ~> F](_.transact(xa)))
      }
    } yield offset

    Stream.eval_(trace(logger)("Starting load")) ++
    result ++
    Stream.eval_(trace(logger)("Finished load"))
  }

  /** Ensures the provided identity column is suitable for indexing by SQL Server,
    * adjusting the type to one that is compatible and indexable if not.
    */
   /*
  private def ensureIndexableIdColumn(
      id: Column[SQLServerType],
      columns: NonEmptyList[Column[ColumnType.Scalar]])
      : (Column[SQLServerType], NonEmptyList[Column[SQLServerType]]) =
    Typer.inferScalar(id.tpe)
      .collect {
        case t @ ColumnType.String if id.tpe.some === Typer.preferred(t) =>
          val indexableId = id.as(SQLServerType.VARCHAR(MaxIndexableVarchars))
          val cols = columns.map(c => if (c === id) indexableId else c)
          (indexableId, cols)
      }
      .getOrElse((id, columns))
      */
}
