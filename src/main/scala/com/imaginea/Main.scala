package com.imaginea

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.Multipart.FormData
import akka.http.scaladsl.model.{Multipart, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatchers, Route}
import akka.stream.ActorMaterializer
import com.imaginea.activegrid.core.models._
import com.imaginea.activegrid.core.utils.{Constants, FileUtils}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import spray.json.DefaultJsonProtocol._
import spray.json.{DeserializationException, JsString, JsValue, RootJsonFormat}

import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.collection.JavaConversions._
import scala.collection.mutable

object Main extends App  {

  implicit val config = ConfigFactory.load
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher
  val logger = Logger(LoggerFactory.getLogger(getClass.getName))
  val logLevelUpdater = new LogLevelUpdater

  implicit object KeyPairStatusFormat extends RootJsonFormat[KeyPairStatus] {
    override def write(obj: KeyPairStatus): JsValue = JsString(obj.name.toString)

    override def read(json: JsValue): KeyPairStatus = json match {
      case JsString(str) => KeyPairStatus.toKeyPairStatus(str)
      case _ => throw DeserializationException("Enum string expected")
    }
  }

  implicit val KeyPairInfoFormat = jsonFormat(KeyPairInfo.apply, "id", "keyName", "keyFingerprint", "keyMaterial", "filePath", "status", "defaultUser", "passPhrase")
  implicit val PageKeyPairInfo = jsonFormat(Page[KeyPairInfo], "startIndex", "count", "totalObjects", "objects")

  implicit val UserFormat = jsonFormat(User.apply, "id", "userName", "password", "email", "uniqueId", "publicKeys", "accountNonExpired", "accountNonLocked", "credentialsNonExpired", "enabled", "displayName")
  implicit val PageUsersFomat = jsonFormat(Page[User], "startIndex", "count", "totalObjects", "objects")

  implicit val SSHKeyContentInfoFormat = jsonFormat(SSHKeyContentInfo, "keyMaterials")
  implicit val softwareFormat = jsonFormat(Software.apply, "id", "version", "name", "provider", "downloadURL", "port", "processNames", "discoverApplications")
  implicit val softwarePageFormat = jsonFormat4(Page[Software])
  implicit val ImageFormat = jsonFormat(ImageInfo.apply, "id", "state", "ownerId", "publicValue", "architecture", "imageType", "platform", "imageOwnerAlias", "name", "description", "rootDeviceType", "rootDeviceName", "version")
  implicit val PageFormat = jsonFormat4(Page[ImageInfo])
  implicit val appSettingsFormat = jsonFormat(AppSettings.apply, "id", "settings", "authSettings")
  implicit val siteFormat = jsonFormat(Site.apply, "id", "siteName", "groupBy")

  implicit object apmProviderFormat extends RootJsonFormat[APMProvider] {
    override def write(obj: APMProvider): JsValue = {
      logger.info(s"Writing APMProvider json : ${obj.provider.toString}")
      JsString(obj.provider.toString)
    }

    override def read(json: JsValue): APMProvider = {
      logger.info(s"Reading json value : ${json.toString}")
      json match {
        case JsString(str) => APMProvider.toProvider(str)
        case _ => throw DeserializationException("Unable to deserialize the Provider data")
      }
    }
  }

  implicit val apmServerDetailsFormat = jsonFormat(APMServerDetails.apply, "id", "name", "serverUrl", "monitoredSite", "provider", "headers")

  def appSettingServiceRoutes = post {
    path("appsettings") {
      entity(as[AppSettings]) {
        appsetting =>
          onComplete(Future {
            appsetting.toNeo4jGraph(appsetting)
          }) {
            case Success(response) => complete(StatusCodes.OK, "Done")
            case Failure(exception) =>
              logger.error(s"Unable to save App Settings ${exception.getMessage}", exception)
              complete(StatusCodes.BadRequest, "Unable to save App Settings")
          }
      }
    }
  } ~ get {
    path("config") {
      val appSettings = Future {
        AppSettings.getAppSettingNode.flatMap(node => AppSettings.fromNeo4jGraph(node.getId))
      }
      onComplete(appSettings) {
        case Success(response) =>
          response match {
            case Some(appsettings) => complete(StatusCodes.OK, appsettings)
            case None => complete(StatusCodes.BadRequest, "Unable to get the App Settings")
          }
        case Failure(exception) =>
          logger.error(s"Unable to get App Settings ${exception.getMessage}", exception)
          complete(StatusCodes.BadRequest, "Unable to get App Settings")
      }
    }
  } ~ put {
    path(PathMatchers.separateOnSlashes("config/settings")) {
      entity(as[Map[String, String]]) {
        setting =>
          val response = Future {
            AppSettings.updateAppSettings(setting, "Has_Settings")
          }
          onComplete(response) {
            case Success(responseMessage) => complete(StatusCodes.OK, "Done")
            case Failure(exception) =>
              logger.error(s"Unable to save the settings ${exception.getMessage}", exception)
              complete(StatusCodes.BadRequest, "Unable to save the settings")
          }
      }
    }
  } ~ put {
    path(PathMatchers.separateOnSlashes("config/settings/auth")) {
      entity(as[Map[String, String]]) {
        setting =>
          val response = Future {
            AppSettings.updateAppSettings(setting, "Has_AuthSettings")
          }
          onComplete(response) {
            case Success(responseMessage) => complete(StatusCodes.OK, "Done")
            case Failure(exception) =>
              logger.error(s"Unable to save the Auth settings ${exception.getMessage}", exception)
              complete(StatusCodes.BadRequest, "Unable to save the Auth settings")
          }
      }
    }
  } ~ path(PathMatchers.separateOnSlashes("config/settings")) {
    get {
      val appSettings = Future {
        AppSettings.getAppSettingNode.flatMap(node => AppSettings.fromNeo4jGraph(node.getId))
      }
      onComplete(appSettings) {
        case Success(response) => complete(StatusCodes.OK, response)
        case Failure(exception) =>
          logger.error(s"Unable to fetch settings ${exception.getMessage}", exception)
          complete(StatusCodes.BadRequest, "Unable to fetch the settings")
      }
    }
  } ~ path(PathMatchers.separateOnSlashes("config/settings")) {
    delete {
      entity(as[List[String]]) { list =>
        val isDeleted = Future {
          AppSettings.deleteSettings(list, "Has_Settings")
        }
        onComplete(isDeleted) {
          case Success(response) => complete(StatusCodes.OK, "Done")
          case Failure(exception) =>
            logger.error(s"Unable to delete settings ${exception.getMessage}", exception)
            complete(StatusCodes.BadRequest, "Unable to delete  settings")
        }
      }
    }
  } ~ path(PathMatchers.separateOnSlashes("config/logs/level")) {
    put {
      entity(as[String]) {
        level =>
          onComplete(logLevelUpdater.setLogLevel(logLevelUpdater.ROOT, level)) {
            case Success(response) => complete(StatusCodes.OK, "Done")
            case Failure(exception) =>
              logger.error(s"Unable to update log level ${exception.getMessage}", exception)
              complete(StatusCodes.BadRequest, "Unable to update log level")
          }
      }
    }
  } ~ path(PathMatchers.separateOnSlashes("config/logs/level")) {
    get {
      val response = logLevelUpdater.getLogLevel(logLevelUpdater.ROOT)
      onComplete(response) {
        case Success(responseMessage) => complete(StatusCodes.OK, responseMessage)
        case Failure(exception) =>
          logger.error(s"Unable to get the log level ${exception.getMessage}", exception)
          complete(StatusCodes.BadRequest, "Unable to get the log level")
      }
    }
  }

  def apmServiceRoutes = path(PathMatchers.separateOnSlashes("apm")) {
    post {
      entity(as[APMServerDetails]) { apmServerDetails =>
        logger.debug(s"Executing $getClass :: saveAPMServerDetails")

        val serverDetails = Future {
          val serverDetailsEnity = apmServerDetails.toNeo4jGraph(apmServerDetails)
          apmServerDetails.fromNeo4jGraph(serverDetailsEnity.getId)
        }
        onComplete(serverDetails) {
          case Success(response) =>
            response match {
              case Some(details) => complete(StatusCodes.OK, response)
              case None => complete(StatusCodes.BadRequest, "Unable to Save Server Details")
            }
          case Failure(exception) =>
            logger.error(s"Unable to save the APM Server Details ${exception.getMessage}", exception)
            complete(StatusCodes.BadRequest, "Unable to save the Server details")
        }
      }
    }
  } ~ path(PathMatchers.separateOnSlashes("apm")) {
    get {
      val serverDetailsList = Future {
        getAPMServers.toList
      }
      onComplete(serverDetailsList) {
        case Success(response) => complete(StatusCodes.OK, response)
        case Failure(exception) =>
          logger.error(s"Unable get the APM Server Details ${exception.getMessage}", exception)
          complete(StatusCodes.BadRequest, "Unable get the APM server details")
      }
    }
  } ~ path("apm" / LongNumber / "url") {
    serverId =>
      get {
        logger.info(s"getting into request context : $serverId")
        val serverDetailsList = Future {
          APMServerDetails.fromNeo4jGraph(serverId).flatMap(serverDetails => Some(serverDetails.serverUrl))
        }
        onComplete(serverDetailsList) {
          case Success(response) =>
            response match {
              case Some(detailsList) => complete(StatusCodes.OK, detailsList)
              case None => complete(StatusCodes.BadRequest, s"Unable to get URL with given ID : $serverId")
            }
          case Failure(exception) =>
            logger.error(s"Unable to get the APM Server Url ${exception.getMessage}", exception)
            complete(StatusCodes.BadRequest, "Unable to get the APM Server Url")
        }
      }
  } ~ path("apm" / IntNumber) {
    siteId => get {

      val serverDetails = Future {
        Site.fromNeo4jGraph(siteId).flatMap { site =>
          val aPMServerDetails = getAPMServers
          logger.info(s"All Sever details : $aPMServerDetails")
          val list = aPMServerDetails.filter(server => {
            if (server.monitoredSite.nonEmpty) server.monitoredSite.get.id == site.id else false
          })
          logger.info(s"Filtered Server details : $list")
          Some(list.toList)
        }
      }
      onComplete(serverDetails) {
        case Success(response) =>
          response match {
            case Some(details) => complete(StatusCodes.OK, details)
            case None => complete(StatusCodes.BadRequest, s"Unable to get APMServer Details with given Site ID : $siteId")
          }
        case Failure(exception) =>
          logger.error(s"Unable to get the APM Server Details : $exception")
          complete(StatusCodes.BadRequest, s"Unable to get the APM Server Details with Site Id :$siteId")
      }
    }
  }

  def userRoute: Route = pathPrefix("users" / LongNumber) { userId =>
    pathPrefix("keys") {
      pathPrefix(LongNumber) { keyId =>
        get {
          val key = Future {
            getKeyById(userId, keyId)
          }
          onComplete(key) {
            case Success(response) =>
              response match {
                case Some(keyPairInfo) => complete(StatusCodes.OK, keyPairInfo)
                case None => complete(StatusCodes.BadRequest, "Unable to get the key")
              }
            case Failure(ex) =>
              logger.error(s"Unable to get the key, Reason: ${ex.getMessage}", ex)
              complete(StatusCodes.BadRequest, s"Unable to get the key")
          }

        } ~ delete {
          val resposne = Future {
            logger.debug(s"Deleting Key[$keyId] of User[$userId] ")
            getKeyById(userId, keyId).flatMap(key => {
              Neo4jRepository.deleteChildNode(keyId)
            })
          }
          onComplete(resposne) {
            case Success(result) => complete(StatusCodes.OK, "Deleted Successfully")
            case Failure(ex) =>
              logger.error(s"Failed delete, Message: ${ex.getMessage}", ex)
              complete(StatusCodes.BadRequest, s"Failed to delete")
          }
        }
      } ~ get {
        val result = Future {
          User.fromNeo4jGraph(userId) match {
            case Some(user) => Page[KeyPairInfo](user.publicKeys)
            case None => Page[KeyPairInfo](List.empty[KeyPairInfo])
          }
        }
        onComplete(result) {
          case Success(page) => complete(StatusCodes.OK, page)
          case Failure(ex) =>
            logger.error(s"Failed get Users, Message: ${ex.getMessage}", ex)
            complete(StatusCodes.BadRequest, s"Failed get Users")
        }
      } ~ post {
        entity(as[SSHKeyContentInfo]) { sshKeyInfo =>
          val result = Future {

            FileUtils.createDirectories(UserUtils.getKeyDirPath(userId))

            val resultKeys = sshKeyInfo.keyMaterials.map { case (keyName: String, keyMaterial: String) =>
              logger.debug(s" ($keyName  --> ($keyMaterial))")
              val filePath: String = UserUtils.getKeyFilePath(userId, keyName)
              FileUtils.saveContentToFile(filePath, keyMaterial)

              val keyPairInfo = KeyPairInfo(keyName, keyMaterial, Some(filePath), UploadedKeyPair)
              logger.debug(s" new Key Pair Info $keyPairInfo")
              UserUtils.addKeyPair(userId, keyPairInfo)
              keyPairInfo
            }

            Page(resultKeys.toList)
          }
          onComplete(result) {
            case Success(page) => complete(StatusCodes.OK, page)
            case Failure(ex) =>
              logger.error(s"Failed to add keys, Message: ${ex.getMessage}", ex)
              complete(StatusCodes.BadRequest, s"Failed to add keys")
          }
        }
      }
    } ~ get {
      val result = Future {
        User.fromNeo4jGraph(userId)
      }
      onComplete(result) {
        case Success(mayBeUser) =>
          mayBeUser match {
            case Some(user) => complete(StatusCodes.OK, user)
            case None => complete(StatusCodes.BadRequest, s"Failed to get user with id $userId")
          }
        case Failure(ex) =>
          logger.error(s"Failed to get user, Message: ${ex.getMessage}", ex)
          complete(StatusCodes.BadRequest, s"Failed to get user, Message: ${ex.getMessage}")
      }
    } ~ delete {
      val result = Future {
        Neo4jRepository.deleteEntity(userId)
      }
      onComplete(result) {
        case Success(status) => complete(StatusCodes.OK, "Deleted succesfully")
        case Failure(ex) =>
          logger.error(s"Failed to delete user, Message: ${ex.getMessage}", ex)
          complete(StatusCodes.BadRequest, s"Failed to delete user, Message: ${ex.getMessage}")
      }
    }
  } ~ pathPrefix("users") {
    get {
      pathPrefix(Segment / "keys") { userName =>
        val result = Future {
          logger.debug(s"Searching Users with name $userName")
          val maybeNode = Neo4jRepository.getSingleNodeByLabelAndProperty("User", "username", userName)

          logger.debug(s" May be node $maybeNode")

          maybeNode match {
            case None => Page(List.empty[KeyPairInfo])
            case Some(node) =>
              User.fromNeo4jGraph(node.getId) match {
                case Some(user) => Page[KeyPairInfo](user.publicKeys)
                case None => Page[KeyPairInfo](List.empty[KeyPairInfo])
              }
          }
        }
        onComplete(result) {
          case Success(page) => complete(StatusCodes.OK, page)
          case Failure(ex) =>
            logger.error(s"Failed to get keys, Message: ${ex.getMessage}", ex)
            complete(StatusCodes.BadRequest, s"Failed to get keys")
        }
      }
    } ~ get {
      val result = Future {
        val nodeList = Neo4jRepository.getNodesByLabel("User")
        val listOfUsers = nodeList.flatMap(node => User.fromNeo4jGraph(node.getId))

        Page[User](listOfUsers)
      }
      onComplete(result) {
        case Success(page) => complete(StatusCodes.OK, page)
        case Failure(ex) =>
          logger.error(s"Failed to get users, Message: ${ex.getMessage}", ex)
          complete(StatusCodes.BadRequest, s"Failed to get users")
      }
    } ~ post {
      entity(as[User]) { user =>
        val result = Future {
          user.toNeo4jGraph(user)
        }
        onComplete(result) {
          case Success(status) => complete(StatusCodes.OK, "Successfully saved user")
          case Failure(ex) =>
            logger.error(s"Failed save user, Message: ${ex.getMessage}", ex)
            complete(StatusCodes.BadRequest, s"Failed save user")
        }
      }
    }
  }

  //KeyPair Serivce
  def keyPairRoute: Route = pathPrefix("keypairs") {
    pathPrefix(LongNumber) { keyId =>
      get {
        val result = Future {
          Neo4jRepository.findNodeByLabelAndId("KeyPairInfo", keyId).flatMap(node => KeyPairInfo.fromNeo4jGraph(node.getId))
        }
        onComplete(result) {
          case Success(mayBekey) =>
            mayBekey match {
              case Some(key) => complete(StatusCodes.OK, key)
              case None => complete(StatusCodes.BadRequest, s"failed to get key pair for id $keyId")
            }
          case Failure(ex) =>
            logger.error(s"Failed to get Key Pair, Message: ${ex.getMessage}", ex)
            complete(StatusCodes.BadRequest, s"Failed to get Key Pair")
        }
      } ~ delete {
        val result = Future {
          Neo4jRepository.deleteChildNode(keyId)
        }
        onComplete(result) {
          case Success(key) => complete(StatusCodes.OK, "Deleted Successfully")
          case Failure(ex) =>
            logger.error(s"Failed to delete Key Pair, Message: ${ex.getMessage}", ex)
            complete(StatusCodes.BadRequest, s"Failed to delete Key Pair")
        }
      }
    } ~ get {
      val result = Future {
        val nodeList = Neo4jRepository.getNodesByLabel("KeyPairInfo")
        logger.debug(s"nodeList $nodeList")
        val listOfKeys = nodeList.flatMap(node => KeyPairInfo.fromNeo4jGraph(node.getId))
        Page[KeyPairInfo](listOfKeys)
      }
      onComplete(result) {
        case Success(page) => complete(StatusCodes.OK, page)
        case Failure(ex) =>
          logger.error(s"Failed to get Keys, Message: ${ex.getMessage}", ex)
          complete(StatusCodes.BadRequest, s"Failed to get Keys")
      }
    } ~ put {
      entity(as[Multipart.FormData]) { formData =>
        val result = Future {

          val dataMap = formData.asInstanceOf[FormData.Strict].strictParts.foldLeft(Map[String, String]())((accum, strict) => {
            val name = strict.getName()
            val value = strict.entity.getData().decodeString("UTF-8")
            val mayBeFile = strict.filename
            logger.debug(s"--- $name  -- $value -- $mayBeFile")

            mayBeFile match {
              case Some(fileName) => accum + ((name, value))
              case None =>
                if (name.equalsIgnoreCase("userName") || name.equalsIgnoreCase("passPhase"))
                  accum + ((name, value))
                else
                  accum
            }
          })

          val sshKeyContentInfo: SSHKeyContentInfo = SSHKeyContentInfo(dataMap)
          logger.debug(s"ssh info   - $sshKeyContentInfo")
          logger.debug(s"Data Map --- $dataMap")
          val userNameLabel = "userName"
          val passPhaseLabel = "passPhase"
          val addedKeyPairs: List[KeyPairInfo] = dataMap.map { case (keyName, keyMaterial) =>
            if (!userNameLabel.equalsIgnoreCase(keyName) && !passPhaseLabel.equalsIgnoreCase(keyName)) {
              val keyPairInfo = getOrCreateKeyPair(keyName, keyMaterial, None, UploadedKeyPair, dataMap.get(userNameLabel), dataMap.get(passPhaseLabel))
              val mayBeNode = saveKeyPair(keyPairInfo)
              mayBeNode match {
                case Some(key) => key
                case _ => // do nothing
              }
            }
          }.toList.collect { case x: KeyPairInfo => x }

          Page[KeyPairInfo](addedKeyPairs)
        }
        onComplete(result) {
          case Success(page) => complete(StatusCodes.OK, page)
          case Failure(ex) =>
            logger.error(s"Failed to update Keys, Message: ${ex.getMessage}", ex)
            complete(StatusCodes.BadRequest, s"Failed to update Keys")
        }
      }
    }
  }


  def catalogRoutes = pathPrefix("catalog") {
    path("images" / "view") {
      get {
        val getImages: Future[Page[ImageInfo]] = Future {
          val imageLabel: String = "ImagesTest2"
          val nodesList = GraphDBExecutor.getNodesByLabel(imageLabel)
          val imageInfoList = nodesList.flatMap(node => ImageInfo.fromNeo4jGraph(node.getId))

          Page[ImageInfo](imageInfoList)
        }

        onComplete(getImages) {
          case Success(successResponse) => complete(StatusCodes.OK, successResponse)
          case Failure(exception) =>
            logger.error(s"Unable to Retrieve ImageInfo List. Failed with : ${exception.getMessage}", exception)
            complete(StatusCodes.BadRequest, "Unable to Retrieve ImageInfo List.")
        }
      }
    } ~ path("images") {
      put {
        entity(as[ImageInfo]) { image =>
          val buildImage = Future {
            image.toNeo4jGraph(image)
            "Successfully added ImageInfo"
          }
          onComplete(buildImage) {
            case Success(successResponse) => complete(StatusCodes.OK, successResponse)
            case Failure(exception) =>
              logger.error(s"Unable to Save Image. Failed with : ${exception.getMessage}", exception)
              complete(StatusCodes.BadRequest, "Unable to Save Image.")
          }
        }
      }
    } ~ path("images" / LongNumber) { imageId =>
      delete {
        val deleteImages = Future {
          GraphDBExecutor.deleteEntity[ImageInfo](imageId)
        }

        onComplete(deleteImages) {
          case Success(successResponse) => complete(StatusCodes.OK, "Successfully deleted ImageInfo")
          case Failure(exception) =>
            logger.error(s"Unable to Delete Image. Failed with : ${exception.getMessage}", exception)
            complete(StatusCodes.BadRequest, "Unable to Delete Image.")
        }
      }
    } ~ path("softwares") {
      put {
        entity(as[Software]) { software =>
          val buildSoftware = Future {
            software.toNeo4jGraph(software)
            "Saved Software Successfully"
          }
          onComplete(buildSoftware) {
            case Success(successResponse) => complete(StatusCodes.OK, successResponse)
            case Failure(exception) =>
              logger.error(s"Unable to Save Software. Failed with : ${exception.getMessage}", exception)
              complete(StatusCodes.BadRequest, "Unable to Save Software.")
          }
        }
      }
    } ~ path("softwares" / LongNumber) { softwareId =>
      delete {
        val deleteSoftware = Future {
          GraphDBExecutor.deleteEntity[Software](softwareId)
          "Deleted Successfully"
        }

        onComplete(deleteSoftware) {
          case Success(successResponse) => complete(StatusCodes.OK, successResponse)
          case Failure(exception) =>
            logger.error(s"Unable to Delete Software. Failed with : ${exception.getMessage}", exception)
            complete(StatusCodes.BadRequest, "Unable to Delete Software.")
        }
      }
    } ~ path("softwares") {
      get {
        val getSoftwares = Future {
          val softwareLabel: String = "SoftwaresTest2"
          val nodesList = GraphDBExecutor.getNodesByLabel(softwareLabel)
          val softwaresList = nodesList.flatMap(node => Software.fromNeo4jGraph(node.getId))
          Page[Software](softwaresList)
        }
        onComplete(getSoftwares) {
          case Success(successResponse) => complete(StatusCodes.OK, successResponse)
          case Failure(exception) =>
            logger.error(s"Unable to Retrieve Softwares List. Failed with :  ${exception.getMessage}", exception)
            complete(StatusCodes.BadRequest, "Unable to Retrieve Softwares List.")
        }
      }
    }
  }

  val route: Route = userRoute ~ keyPairRoute ~ catalogRoutes ~ appSettingServiceRoutes ~ apmServiceRoutes

  val bindingFuture = Http().bindAndHandle(route, config.getString("http.host"), config.getInt("http.port"))
  logger.info(s"Server online at http://${config.getString("http.host")}:${config.getInt("http.port")}")


  def getKeyById(userId: Long, keyId: Long): Option[KeyPairInfo] = {
    User.fromNeo4jGraph(userId) match {
      case Some(user) => user.publicKeys.dropWhile(_.id.get != keyId).headOption
      case None => None
    }
  }

  def getOrCreateKeyPair(keyName: String, keyMaterial: String, keyFilePath: Option[String], status: KeyPairStatus, defaultUser: Option[String], passPhase: Option[String]): KeyPairInfo = {
    val mayBeKeyPair = Neo4jRepository.getSingleNodeByLabelAndProperty("KeyPairInfo", "keyName", keyName).flatMap(node => KeyPairInfo.fromNeo4jGraph(node.getId))

    mayBeKeyPair match {
      case Some(keyPairInfo) =>
        KeyPairInfo(keyPairInfo.id, keyName, keyPairInfo.keyFingerprint, keyMaterial, if (keyFilePath.isEmpty) keyPairInfo.filePath else keyFilePath, status, if (defaultUser.isEmpty) keyPairInfo.defaultUser else defaultUser, if (passPhase.isEmpty) keyPairInfo.passPhrase else passPhase)
      case None => KeyPairInfo(keyName, keyMaterial, keyFilePath, status)
    }
  }

  def saveKeyPair(keyPairInfo: KeyPairInfo): Option[KeyPairInfo] = {
    val filePath = getKeyFilePath(keyPairInfo.keyName)
    try {
      FileUtils.createDirectories(getKeyFilesDir)
      FileUtils.saveContentToFile(filePath, keyPairInfo.keyMaterial)
      // TODO: change permissions to 600
    } catch {
      case e: Throwable => logger.error(e.getMessage, e)
    }
    val node = keyPairInfo.toNeo4jGraph(KeyPairInfo(keyPairInfo.id, keyPairInfo.keyName, keyPairInfo.keyFingerprint, keyPairInfo.keyMaterial, Some(filePath), keyPairInfo.status, keyPairInfo.defaultUser, keyPairInfo.passPhrase))
    KeyPairInfo.fromNeo4jGraph(node.getId)
  }

  def getKeyFilesDir: String = s"${Constants.getTempDirectoryLocation}${Constants.FILE_SEPARATOR}"

  def getKeyFilePath(keyName: String) = s"$getKeyFilesDir$keyName.pem"

  def getAPMServers: mutable.MutableList[APMServerDetails] = {
    logger.debug(s"Executing $getClass :: getAPMServers")
    val nodes = APMServerDetails.getAllEntities
    logger.debug(s"Getting all entities and size is :${nodes.size}")
    val list = mutable.MutableList.empty[APMServerDetails]
    nodes.foreach {
      node =>
        val aPMServerDetails = APMServerDetails.fromNeo4jGraph(node.getId)
        aPMServerDetails match {
          case Some(serverDetails) => list.+=(serverDetails)
          case _ => logger.warn(s"Node not found with ID: ${node.getId}")
        }
    }
    logger.debug(s"Reurning list of APM Servers $list")
    list
  }
}

