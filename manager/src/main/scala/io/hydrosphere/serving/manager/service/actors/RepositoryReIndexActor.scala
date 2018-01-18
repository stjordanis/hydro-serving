package io.hydrosphere.serving.manager.service.actors

import java.time.{Instant, Duration => JDuration}

import akka.actor.Props
import akka.util.Timeout
import io.hydrosphere.serving.manager.service.ModelManagementService
import io.hydrosphere.serving.manager.service.modelsource.FileEvent

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

class RepositoryReIndexActor(val modelManagementService: ModelManagementService)
  extends SelfScheduledActor(0.seconds, 100.millis)(Timeout(30.seconds)) {
  import context._
  context.system.eventStream.subscribe(self, classOf[FileEvent])

  // model name -> last event
  private[this] val queues = TrieMap.empty[String, FileEvent]

  override def recieveNonTick: Receive = {
    case e: FileEvent =>
      val modelName = e.filename.split("/").head
      println(s"[${e.source.sourceDef.prefix}] Detected a modification of $modelName model ...")
      queues += modelName -> e
  }

  override def onTick(): Unit = {
    val now = Instant.now()
    val upgradeable =  queues.toList.filter{
      case (_, event) =>
        val diff = JDuration.between(event.timestamp, now)
        diff.getSeconds > 10 // TODO move to config
    }

    upgradeable.foreach{
      case (modelname, event) =>
        println(s"[${event.source.sourceDef.prefix}] Reindexing $modelname ...")
        queues -= modelname
        val r = modelManagementService.updateModel(modelname, event.source)
        r.foreach( x => println(s"$x is updated"))
    }
  }

}

object RepositoryReIndexActor {
  def props(modelManagementService: ModelManagementService) = Props(new RepositoryReIndexActor(modelManagementService))
}
