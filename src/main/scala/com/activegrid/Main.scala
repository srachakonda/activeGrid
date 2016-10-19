package com.activegrid

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatchers
import akka.stream.ActorMaterializer
import com.activegrid.models._
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.Future


object Main extends App with JsonSupport {

  override val logger = LoggerFactory.getLogger(getClass.getName)

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  val logLevelUpdater = new LogLevelUpdater

  def appSettingServiceRoutes = post {
    path("appsettings") {
      entity(as[AppSettings]) {
        appsetting =>
          onComplete(Future {
            appsetting.toNeo4jGraph()
          }) {
            case util.Success(response) => complete(StatusCodes.OK, "Done")
            case util.Failure(exception) => {
              logger.error(s"Unable to save App Settings $exception")
              complete(StatusCodes.BadRequest, "Unable to save App Settings")
            }
          }
      }
    }
  } ~ get {
    path("config") {
      val nodeId = AppSettings.getAppSettingNode() match {
        case Some(node) => Some(node.getId)
        case None => None
      }
      val appSettings = Future {
        AppSettings.fromNeo4jGraph(nodeId)
      }
      onComplete(appSettings) {
        case util.Success(response) => {
          response match {
            case Some(appSettings) => complete(StatusCodes.OK, appSettings)
            case None => complete(StatusCodes.OK, "Unable to get the App Settings")
          }
        }
        case util.Failure(exception) => {
          logger.error(s"Unable to get App Settings")
          complete(StatusCodes.BadRequest, "Unable to get App Settings")
        }
      }

    }
  } ~ post {
    path(PathMatchers.separateOnSlashes("config/settings")) {
      entity(as[Map[String, String]]) {
        setting =>
          val response = Future {
            AppSettings.updateAppSettings(setting, "Has_Settings")
          }
          onComplete(response) {
            case util.Success(response) => complete(StatusCodes.OK, "Done")
            case util.Failure(exception) => {
              logger.error(s"Unable to save the settings $exception")
              complete(StatusCodes.BadRequest, "Unable to save the settings")
            }
          }
      }
    }
  } ~ path(PathMatchers.separateOnSlashes("config/settings")) {
    get {
      val nodeId = AppSettings.getAppSettingNode() match {
        case Some(node) => Some(node.getId)
        case None => None
      }
      val appSettings = Future {
        AppSettings.fromNeo4jGraph(nodeId)
      }
      onComplete(appSettings) {
        case util.Success(response) => complete(StatusCodes.OK, response)
        case util.Failure(exception) => {
          logger.error(s"Unable to fetch settings $exception")
          complete(StatusCodes.BadRequest, "Unable to fetch the settings")
        }
      }
    }
  } ~ path(PathMatchers.separateOnSlashes("config/settings")) {
    delete {
      entity(as[List[String]]) { list =>
        val isDeleted = Future {
          AppSettings.deleteSettings(list, "Has_Settings")
        }
        onComplete(isDeleted) {
          case util.Success(response) => complete(StatusCodes.OK, "Done")
          case util.Failure(exception) => {
            logger.error(s"Unable to delete settings $exception")
            complete(StatusCodes.BadRequest, "Unable to delete  settings")
          }
        }
      }
    }
  } ~ path(PathMatchers.separateOnSlashes("config/logs/level")) {
    put {
      entity(as[String]) {
        level =>
          onComplete(logLevelUpdater.setLogLevel(logLevelUpdater.ROOT, level)) {
            case util.Success(response) => complete(StatusCodes.OK, "Done")
            case util.Failure(exception) => {
              logger.error(s"Unable to update log level $exception")
              complete(StatusCodes.BadRequest, "Unable to update log level")
            }
          }

      }
    }
  } ~ path(PathMatchers.separateOnSlashes("config/logs/level")) {
    get {
      val response = logLevelUpdater.getLogLevel(logLevelUpdater.ROOT)
      onComplete(response) {
        case util.Success(response) => complete(StatusCodes.OK, response)
        case util.Failure(exception) => {
          logger.error(s"Unable to get the log level $exception")
          complete(StatusCodes.BadRequest, "Unable to get the log level")
        }
      }
    }
  }

  def apmServiceRoutes = path(PathMatchers.separateOnSlashes("apm")) {
    post {
      entity(as[APMServerDetails]) { apmServerDetails =>
        logger.debug(s"Executing $getClass :: saveAPMServerDetails")
        val serverDetailsEnity = apmServerDetails.toNeo4jGraph()
        val serverDetails = Future {
          serverDetailsEnity match {
            case Some(serverDetailsEntity) => {
              apmServerDetails.fromNeo4jGraph(Some(serverDetailsEnity.get.getId))
            }
            case None => None
          }
        }
        onComplete(serverDetails) {
          case util.Success(response) => {
            response match {
              case Some(details) => complete(StatusCodes.OK, response)
              case None => complete(StatusCodes.OK, "Unable to Save Server Details")
            }
          }
          case util.Failure(exception) => {
            logger.error(s"Unable to save the APM Server Details $exception")
            complete(StatusCodes.BadRequest, "Unable to save the Server details")
          }
        }
      }
    }
  } ~ path(PathMatchers.separateOnSlashes("apm")) {
    get {
      val serverDetailsList = Future {
        getAPMServers().toList
      }
      onComplete(serverDetailsList) {
        case util.Success(response) => complete(StatusCodes.OK, response)
        case util.Failure(exception) => {
          logger.error(s"Unable get the APM Server Details $exception")
          complete(StatusCodes.BadRequest, "Unable get the APM server details")
        }
      }
    }
  } ~ path("apm" / LongNumber / "url") {
    serverId =>
      get {
        logger.info(s"getting into request context : $serverId")

        val serverDetails = APMServerDetails.fromNeo4jGraph(Some(serverId))
        val serverDetailsList = Future {
          serverDetails match {
            case Some(serverDetails) => Some(serverDetails.serverUrl)
            case None => None
          }
        }
        onComplete(serverDetailsList) {
          case util.Success(response) => {
            response match {
              case Some(detailsList) => complete(StatusCodes.OK, detailsList)
              case None => complete(StatusCodes.BadRequest, s"Unable to get URL with given ID : $serverId")
            }
          }
          case util.Failure(exception) => {
            logger.error(s"Unable to get the APM Server Url $exception")
            complete(StatusCodes.BadRequest, "Unable to get the APM Server Url")
          }
        }
      }
  } ~ path("apm" / IntNumber) {
    siteId => get {
      val site = Site.fromNeo4jGraph(Some(siteId))
      val serverDetails = Future {
        site match {
          case Some(site) => {
            val aPMServerDetails = getAPMServers()
            logger.info(s"All Sever details : $aPMServerDetails")
            val list = aPMServerDetails.filter(server => {
              if (!server.monitoredSite.isEmpty) server.monitoredSite.get.id == site.id else false
            })
            logger.info(s"Filtered Server details : $list")
            Some(list.toList)
          }
          case None => None
        }
      }
      onComplete(serverDetails) {
        case util.Success(response) =>
          response match {
            case Some(details) => complete(StatusCodes.OK, details)
            case None => complete(StatusCodes.BadRequest, s"Unable to get APMServer Details with given Site ID : $siteId")
          }
        case util.Failure(exception) =>
          logger.error(s"Unable to get the APM Server Details : ${exception.getMessage}")
          complete(StatusCodes.BadRequest, exception.getMessage)
      }
    }
  }

  val routes = appSettingServiceRoutes ~ apmServiceRoutes
  Http().bindAndHandle(routes, "localhost", 8000)
  logger.info(s"Server online at http://localhost:8000")

  def getAPMServers(): mutable.MutableList[APMServerDetails] = {
    logger.debug(s"Executing $getClass :: getAPMServers")
    val nodes = APMServerDetails.getAllEntities()
    logger.debug(s"Getting all entities and size is :${nodes.size}")
    val list = mutable.MutableList.empty[APMServerDetails]
    nodes.foreach {
      node =>
        val aPMServerDetails = APMServerDetails.fromNeo4jGraph(Some(node.getId))
        aPMServerDetails match {
          case Some(serverDetails) => list.+=(serverDetails)
          case _ => logger.info(s"Node not found with ID: ${node.getId}")
        }

    }
    logger.debug(s"Reurning list of APM Servers $list")
    list
  }
}

