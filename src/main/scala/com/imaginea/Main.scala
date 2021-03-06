package com.imaginea


import java.io.File

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
import org.neo4j.graphdb.NotFoundException
import org.slf4j.LoggerFactory
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{Failure, Success}

object Main extends App {

  implicit val config = ConfigFactory.load
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher
  val logger = Logger(LoggerFactory.getLogger(getClass.getName))

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
  implicit val ImageFormat = jsonFormat(ImageInfo.apply, "id", "imageId", "state", "ownerId", "publicValue", "architecture", "imageType", "platform", "imageOwnerAlias", "name", "description", "rootDeviceType", "rootDeviceName", "version")
  implicit val PageImageFormat = jsonFormat4(Page[ImageInfo])
  implicit val appSettingsFormat = jsonFormat(AppSettings.apply, "id", "settings", "authSettings")
  implicit val portRangeFormat = jsonFormat(PortRange.apply, "id", "fromPort", "toPort")
  implicit val sshAccessInfoFormat = jsonFormat(SSHAccessInfo.apply, "id", "keyPair", "userName", "port")
  implicit val instanceConnectionFormat = jsonFormat(InstanceConnection.apply, "id", "sourceNodeId", "targetNodeId", "portRanges")
  implicit val processInfoFormat = jsonFormat(ProcessInfo.apply, "id", "pid", "parentPid", "name", "command", "owner", "residentBytes", "software", "softwareVersion")
  implicit val instanceUserFormat = jsonFormat(InstanceUser.apply, "id", "userName", "publicKeys")
  implicit val InstanceFlavorFormat = jsonFormat(InstanceFlavor.apply, "name", "cpuCount", "memory", "rootDisk")
  implicit val PageInstFormat = jsonFormat4(Page[InstanceFlavor])
  implicit val storageInfoFormat = jsonFormat(StorageInfo.apply, "id", "used", "total")
  implicit val KeyValueInfoFormat = jsonFormat(KeyValueInfo.apply, "id", "key", "value")

  implicit object InstanceProviderFormat extends RootJsonFormat[InstanceProvider] {

    override def write(obj: InstanceProvider): JsValue = {
      JsString(obj.instanceProvider.toString)
    }

    override def read(json: JsValue): InstanceProvider = {
      json match {
        case JsString(str) => InstanceProvider.toInstanceProvider(str)
        case _ => throw DeserializationException("Unable to deserialize Filter Type")
      }
    }
  }

  implicit val accountInfoFormat = jsonFormat(AccountInfo.apply, "id", "accountId", "providerType", "ownerAlias", "accessKey", "secretKey", "regionName", "regions", "networkCIDR")
  implicit val snapshotInfoFormat = jsonFormat11(SnapshotInfo.apply)
  implicit val volumeInfoFormat = jsonFormat11(VolumeInfo.apply)
  implicit val instanceBlockingFormat = jsonFormat7(InstanceBlockDeviceMappingInfo.apply)

  implicit object ipProtocolFormat extends RootJsonFormat[IpProtocol] {

    override def write(obj: IpProtocol): JsValue = {
      JsString(obj.value.toString)
    }

    override def read(json: JsValue): IpProtocol = {
      json match {
        case JsString(str) => IpProtocol.toProtocol(str)
        case _ => throw DeserializationException("Unable to deserialize Filter Type")
      }
    }
  }

  implicit val ipPermissionInfoFormat = jsonFormat6(IpPermissionInfo.apply)
  implicit val securityGroupsFormat = jsonFormat7(SecurityGroupInfo.apply)

  implicit object InstanceFormat extends RootJsonFormat[Instance] {

    def write(i: Instance): JsValue = {

      val fieldNames = List("id", "instanceId", "name", "state", "instanceType", "platform", "architecture", "publicDnsName", "launchTime", "memoryInfo", "rootDiskInfo",
        "tags", "sshAccessInfo", "liveConnections", "estimatedConnections", "processes", "image", "existingUsers", "account", "availabilityZone", "privateDnsName",
        "privateIpAddress", "publicIpAddress", "elasticIp", "monitoring", "rootDeviceType", "blockDeviceMappings", "securityGroups", "reservedInstance", "region")

      val fields = new collection.mutable.ListBuffer[(String, JsValue)]
      fields ++= longToJsField(fieldNames(1), i.id)
      fields ++= stringToJsField(fieldNames(2), i.state)
      fields ++= stringToJsField(fieldNames(3), i.state)
      fields ++= List((fieldNames(4), JsString(i.name)))
      fields ++= stringToJsField(fieldNames(5), i.instanceType)
      fields ++= stringToJsField(fieldNames(6), i.platform)
      fields ++= stringToJsField(fieldNames(7), i.architecture)
      fields ++= stringToJsField(fieldNames(8), i.publicDnsName)
      fields ++= longToJsField(fieldNames(9), i.launchTime)
      fields ++= objectToJsValue[StorageInfo](fieldNames(10), i.memoryInfo, storageInfoFormat)
      fields ++= objectToJsValue[StorageInfo](fieldNames(11), i.rootDiskInfo, storageInfoFormat)
      fields ++= listToJsValue[KeyValueInfo](fieldNames(12), i.tags, KeyValueInfoFormat)
      fields ++= objectToJsValue[SSHAccessInfo](fieldNames(13), i.sshAccessInfo, sshAccessInfoFormat)
      fields ++= listToJsValue[InstanceConnection](fieldNames(14), i.liveConnections, instanceConnectionFormat)
      fields ++= listToJsValue[InstanceConnection](fieldNames(15), i.estimatedConnections, instanceConnectionFormat)
      fields ++= setToJsValue[ProcessInfo](fieldNames(16), i.processes, processInfoFormat)
      fields ++= objectToJsValue[ImageInfo](fieldNames(17), i.image, ImageFormat)
      fields ++= listToJsValue[InstanceUser](fieldNames(18), i.existingUsers, instanceUserFormat)
      fields ++= objectToJsValue[AccountInfo](fieldNames(19), i.account, accountInfoFormat)
      fields ++= stringToJsField(fieldNames(20), i.availabilityZone)
      fields ++= stringToJsField(fieldNames(21), i.privateDnsName)
      fields ++= stringToJsField(fieldNames(22), i.privateIpAddress)
      fields ++= stringToJsField(fieldNames(23), i.publicIpAddress)
      fields ++= stringToJsField(fieldNames(24), i.elasticIP)
      fields ++= stringToJsField(fieldNames(25), i.monitoring)
      fields ++= stringToJsField(fieldNames(26), i.rootDeviceType)
      fields ++= listToJsValue[InstanceBlockDeviceMappingInfo](fieldNames(27), i.blockDeviceMappings, instanceBlockingFormat)
      fields ++= listToJsValue[SecurityGroupInfo](fieldNames(28), i.securityGroups, securityGroupsFormat)
      fields ++= List((fieldNames(29), JsBoolean(i.reservedInstance)))
      fields ++= stringToJsField(fieldNames(30), i.region)
      JsObject(fields: _*)
    }

    def read(value: JsValue) = value match {
      case _ => deserializationError("Instance expected")
    }

    def stringToJsField(fieldName: String, fieldValue: Option[String], rest: List[JsField] = Nil): List[(String, JsValue)] = {
      fieldValue match {
        case Some(x) => (fieldName, JsString(x)) :: rest
        case None => rest
      }
    }

    def longToJsField(fieldName: String, fieldValue: Option[Long], rest: List[JsField] = Nil): List[(String, JsValue)] = {
      fieldValue match {
        case Some(x) => (fieldName, JsNumber(x)) :: rest
        case None => rest
      }
    }

    def objectToJsValue[T](fieldName: String, obj: Option[T], jsonFormat: RootJsonFormat[T], rest: List[JsField] = Nil): List[(String, JsValue)] = {
      obj match {
        case Some(x) => (fieldName, jsonFormat.write(x.asInstanceOf[T])) :: rest
        case None => rest
      }
    }

    def listToJsValue[T](fieldName: String, objList: List[T], jsonFormat: RootJsonFormat[T], rest: List[JsField] = Nil): List[(String, JsValue)] = {
      objList.map { obj => (fieldName, jsonFormat.write(obj))
      }
    }

    def setToJsValue[T](fieldName: String, objList: Set[T], jsonFormat: RootJsonFormat[T], rest: List[JsField] = Nil): List[(String, JsValue)] = {
      objList.map { obj => (fieldName, jsonFormat.write(obj))
      }.toList
    }
  }

  implicit val PageInstanceFormat = jsonFormat4(Page[Instance])
  implicit val siteFormat = jsonFormat(Site.apply, "id", "instances", "siteName", "groupBy")
  implicit val appSettings = jsonFormat(ApplicationSettings.apply, "id", "settings", "authSettings")
  implicit val ResourceACLFormat = jsonFormat(ResourceACL.apply, "id", "resources", "permission", "resourceIds")
  implicit val UserGroupFormat = jsonFormat(UserGroup.apply, "id", "name", "users", "accesses")
  implicit val PageUserGroupFormat = jsonFormat(Page[UserGroup], "startIndex", "count", "totalObjects", "objects")
  implicit val SiteACLFormat = jsonFormat(SiteACL.apply, "id", "name", "site", "instances", "groups")
  implicit val PageSiteACLFormat = jsonFormat(Page[SiteACL], "startIndex", "count", "totalObjects", "objects")

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

  implicit object filterTypeFormat extends RootJsonFormat[FilterType] {
    override def write(obj: FilterType): JsValue = {
      logger.info(s"Writing FilterType json : ${obj.filterType.toString}")
      JsString(obj.filterType.toString)
    }

    override def read(json: JsValue): FilterType = {
      logger.info(s"Reading json value : ${json.toString}")
      json match {
        case JsString(str) => FilterType.toFilteType(str)
        case _ => throw DeserializationException("Unable to deserialize the Provider data")
      }
    }
  }


  implicit val FilterFormat = jsonFormat(Filter.apply, "id", "filterType", "values")
  implicit val SiteFilterFormat = jsonFormat(SiteFilter.apply, "id", "accountInfo", "filters")
  implicit val LoadBalancerFormat = jsonFormat(LoadBalancer.apply, "id", "name", "vpcId", "region", "instanceIds", "availabilityZones")
  implicit val scalingGroupFormat = jsonFormat(ScalingGroup.apply, "id", "name", "launchConfigurationName", "status", "availabilityZones", "instanceIds", "loadBalancerNames", "tags", "desiredCapacity", "maxCapacity", "minCapacity")
  implicit val site1Format = jsonFormat(Site1.apply, "id", "siteName", "instances", "filters", "loadBalancers", "scalingGroups")
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

  def userRoute: Route = pathPrefix("users") {
    path("groups" / LongNumber) { id =>
      get {
        val result = Future {
          UserGroup.fromNeo4jGraph(id)
        }
        onComplete(result) {
          case Success(mayBeUserGroup) =>
            mayBeUserGroup match {
              case Some(userGroup) => complete(StatusCodes.OK, userGroup)
              case None => complete(StatusCodes.BadRequest, s"Failed to get user group with id $id")
            }
          case Failure(ex) =>
            logger.error(s"Failed to get user group, Message: ${ex.getMessage}", ex)
            complete(StatusCodes.BadRequest, s"Failed to get user group, Message: ${ex.getMessage}")
        }
      } ~
        delete {
          val result = Future {
            Neo4jRepository.deleteEntity(id)
          }
          onComplete(result) {
            case Success(status) => complete(StatusCodes.OK, "Deleted succesfully")
            case Failure(ex) =>
              logger.error(s"Failed to delete user group, Message: ${ex.getMessage}", ex)
              complete(StatusCodes.BadRequest, s"Failed to delete user group, Message: ${ex.getMessage}")
          }
        }
    } ~
      path("groups") {
        get {
          val result = Future {
            val nodeList = Neo4jRepository.getNodesByLabel(UserGroup.label)
            val listOfUserGroups = nodeList.flatMap(node => UserGroup.fromNeo4jGraph(node.getId))
            Page[UserGroup](listOfUserGroups)
          }
          onComplete(result) {
            case Success(page) => complete(StatusCodes.OK, page)
            case Failure(ex) =>
              logger.error(s"Failed to get users, Message: ${ex.getMessage}", ex)
              complete(StatusCodes.BadRequest, s"Failed to get users")
          }
        } ~ post {
          entity(as[UserGroup]) { userGroup =>
            val result = Future {
              userGroup.toNeo4jGraph(userGroup)
            }
            onComplete(result) {
              case Success(status) => complete(StatusCodes.OK, "User group saved  Successfully")
              case Failure(ex) =>
                logger.error(s"Failed save user group, Message: ${ex.getMessage}", ex)
                complete(StatusCodes.BadRequest, s"Failed save user group")
            }
          }
        }
      }
  } ~
    pathPrefix("users") {
      path("access" / LongNumber) { id =>
        get {
          val result = Future {
            SiteACL.fromNeo4jGraph(id)
          }
          onComplete(result) {
            case Success(mayBeSiteACL) =>
              mayBeSiteACL match {
                case Some(userGroup) => complete(StatusCodes.OK, userGroup)
                case None => complete(StatusCodes.BadRequest, s"Failed to get user access with id $id")
              }
            case Failure(ex) =>
              logger.error(s"Failed to get user access, Message: ${ex.getMessage}", ex)
              complete(StatusCodes.BadRequest, s"Failed to get access, Message: ${ex.getMessage}")
          }
        } ~
          delete {
            val result = Future {
              Neo4jRepository.deleteEntity(id)
            }
            onComplete(result) {
              case Success(status) => complete(StatusCodes.OK, "Deleted succesfully")
              case Failure(ex) =>
                logger.error(s"Failed to delete user access, Message: ${ex.getMessage}", ex)
                complete(StatusCodes.BadRequest, s"Failed to delete user access, Message: ${ex.getMessage}")
            }
          }
      } ~
        path("access") {
          get {
            val result = Future {
              val nodeList = Neo4jRepository.getNodesByLabel(SiteACL.label)
              val listOfSiteACL = nodeList.flatMap(node => SiteACL.fromNeo4jGraph(node.getId))
              Page[SiteACL](listOfSiteACL)
            }
            onComplete(result) {
              case Success(page) => complete(StatusCodes.OK, page)
              case Failure(ex) =>
                logger.error(s"Failed to get user access, Message: ${ex.getMessage}", ex)
                complete(StatusCodes.BadRequest, s"Failed to get user access")
            }
          } ~ post {
            entity(as[SiteACL]) { siteACL =>
              val result = Future {
                siteACL.toNeo4jGraph(siteACL)
              }
              onComplete(result) {
                case Success(status) => complete(StatusCodes.OK, "Site access saved  Successfully")
                case Failure(ex) =>
                  logger.error(s"Failed save Site access, Message: ${ex.getMessage}", ex)
                  complete(StatusCodes.BadRequest, s"Failed save Site access")
              }
            }

          }
        }
    } ~
    pathPrefix("users" / LongNumber) { userId =>
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
    } ~ path("instanceTypes" / IntNumber) { siteId =>
      get {
        val listOfInstanceFlavors = Future {
          val mayBeSite = Site.fromNeo4jGraph(siteId)
          mayBeSite match {
            case Some(site) =>
              val listOfInstances = site.instances
              val listOfInstanceFlavors = listOfInstances.map(instance => InstanceFlavor(instance.instanceType.get, None, instance.memoryInfo.get.total, instance.rootDiskInfo.get.total))
              Page[InstanceFlavor](listOfInstanceFlavors)

            case None =>
              logger.warn(s"Failed while doing fromNeo4jGraph of Site for siteId : $siteId")
              Page[InstanceFlavor](List.empty[InstanceFlavor])
          }
        }
        onComplete(listOfInstanceFlavors) {
          case Success(successResponse) => complete(StatusCodes.OK, successResponse)
          case Failure(ex) =>
            logger.error(s"Unable to get List; Failed with ${ex.getMessage}", ex)
            complete(StatusCodes.BadRequest, "Unable to get List of Instance Flavors")
        }
      }
    }
  }

  def nodeRoutes = pathPrefix("node") {
    path("list") {
      get {
        val listOfAllInstanceNodes = Future {
          logger.info("Received GET request for all nodes")
          val label: String = "Instance"
          val nodesList = GraphDBExecutor.getNodesByLabel(label)
          val instanceList = nodesList.flatMap(node => Instance.fromNeo4jGraph(node.getId))
          Page[Instance](instanceList)
        }
        onComplete(listOfAllInstanceNodes) {
          case Success(successResponse) => complete(StatusCodes.OK, successResponse)
          case Failure(ex) =>
            logger.error(s"Unable to get Instance nodes; Failed with ${ex.getMessage}", ex)
            complete(StatusCodes.BadRequest, s"Unable to get Instance nodes")
        }
      }
    } ~ path("topology") {
      get {
        val topology = Future {
          logger.debug("received GET request for topology")
          Page[Instance](List.empty[Instance])
        }
        onComplete(topology) {
          case Success(successResponse) => complete(StatusCodes.OK, successResponse)
          case Failure(ex) =>
            logger.error(s"Unable to get Instance nodes; Failed with ${ex.getMessage}", ex)
            complete(StatusCodes.BadRequest, s"Unable to get Instance nodes")
        }
      }
    } ~ path(Segment) { name =>
      get {
        val nodeInstance = Future {
          logger.info(s"Received GET request for node - $name")
          if (name == "localhost") {
            val instance = Instance(name)
            instance.toNeo4jGraph(instance)
          }
          val instanceNode = GraphDBExecutor.getNodeByProperty("Instance", "name", name)
          instanceNode match {
            case Some(node) => Instance.fromNeo4jGraph(node.getId).get
            case None =>
              val name = "echo node"
              val tags: List[KeyValueInfo] = List(KeyValueInfo(None, "tag", "tag"))
              val processInfo = ProcessInfo(1, 1, "init")
              Instance(name, tags, Set(processInfo))
          }
        }
        onComplete(nodeInstance) {
          case Success(successResponse) => complete(StatusCodes.OK, successResponse)
          case Failure(ex) =>
            logger.error(s"Unable to get Instance with name $name; Failed with ${ex.getMessage}", ex)
            complete(StatusCodes.BadRequest, s"Unable to get Instance with name $name")
        }
      }
    }
  }

  val appsettingRoutes = pathPrefix("config") {
    path("ApplicationSettings") {
      post {
        entity(as[ApplicationSettings]) { appSettings =>
          val maybeAdded = Future {
            appSettings.toNeo4jGraph(appSettings)
          }
          onComplete(maybeAdded) {
            case Success(save) => complete(StatusCodes.OK, "Settings saved successfully")
            case Failure(ex) =>
              logger.error("Error while save settings", ex)
              complete(StatusCodes.InternalServerError, "These is problem while processing request")
          }

        }
      }
    }
  } ~ pathPrefix("config") {
    path("ApplicationSettings") {
      get {
        val allSettings = Future {
          AppSettingsNeo4jWrapper.fromNeo4jGraph(0L)
        }
        onComplete(allSettings) {
          case Success(settings) =>
            complete(StatusCodes.OK, settings)
          case Failure(ex) =>
            logger.error("Failed to get settings", ex)
            complete("Failed to get settings")
        }
      }
    }
  } ~ pathPrefix("config") {
    path("ApplicationSettings") {
      put {
        entity(as[Map[String, String]]) { appSettings =>
          val maybeUpdated = AppSettingsNeo4jWrapper.updateSettings(appSettings, "GENERAL_SETTINGS")
          onComplete(maybeUpdated) {
            case Success(update) => update.status match {
              case true => complete(StatusCodes.OK, "Updated successfully")
              case false => complete(StatusCodes.OK, "Updated failed,,Retry!!")
            }
            case Failure(ex) =>
              ex match {
                case aie: IllegalArgumentException =>
                  logger.error("Update operation failed", ex)
                  complete(StatusCodes.OK, "Failed to update settings")
                case _ =>
                  logger.error("Update operation failed", ex)
                  complete(StatusCodes.InternalServerError, "These is problem while processing request")
              }
          }
        }
      }
    }

  } ~ pathPrefix("config") {
    path("AuthSettings") {
      put {
        entity(as[Map[String, String]]) { appSettings =>
          val maybeUpdated = AppSettingsNeo4jWrapper.updateSettings(appSettings, "AUTH_SETTINGS")
          onComplete(maybeUpdated) {
            case Success(update) => update.status match {
              case true => complete(StatusCodes.OK, "Updated successfully")
              case false => complete(StatusCodes.OK, "Updated failed,,Retry!!")
            }
            case Failure(ex) =>
              ex match {
                case aie: IllegalArgumentException =>
                  logger.error("Update operation failed", ex)
                  complete(StatusCodes.OK, "Failed to update settings")
                case _ =>
                  logger.error("Update operation failed", ex)
                  complete(StatusCodes.InternalServerError, "These is problem while processing request")
              }
          }
        }
      }
    }

  } ~ pathPrefix("config") {
    path("ApplicationSettings") {
      delete {
        entity(as[Map[String, String]]) { appSettings =>
          val maybeDeleted = AppSettingsNeo4jWrapper.deleteSetting(appSettings, "GENERAL_SETTINGS")
          onComplete(maybeDeleted) {
            case Success(delete) => delete.status match {
              case true => complete(StatusCodes.OK, "Deleted successfully")
              case false => complete(StatusCodes.OK, "Deletion failed,,Retry!!")
            }
            case Failure(ex) =>
              ex match {
                case aie: IllegalArgumentException =>
                  logger.error("Delete operation failed", ex)
                  complete(StatusCodes.OK, "Failed to delete settings")
                case _ =>
                  logger.error("Delete operation failed", ex)
                  complete(StatusCodes.InternalServerError, "These is problem while processing request")
              }
          }
        }
      }
    }
  } ~ pathPrefix("config") {
    path("AuthSettings") {
      delete {
        entity(as[Map[String, String]]) { appSettings =>
          val maybeDelete = AppSettingsNeo4jWrapper.deleteSetting(appSettings, "AUTH_SETTINGS")
          onComplete(maybeDelete) {
            case Success(delete) => delete.status match {
              case true => complete(StatusCodes.OK, "Deleted successfully")
              case false => complete(StatusCodes.OK, "Deletion failed,,Retry!!")
            }
            case Failure(ex) =>
              ex match {
                case aie: IllegalArgumentException =>
                  logger.error("Delete operation failed", ex)
                  complete(StatusCodes.OK, "Failed to delete settings")
                case _ =>
                  logger.error("Delete operation failed", ex)
                  complete(StatusCodes.InternalServerError, "These is problem while processing request")
              }
          }
        }
      }
    }
  }

  def discoveryRoutes = pathPrefix("discover") {
    path("site" / LongNumber / "instance" / Segment) { (siteId, instanceId) =>
      get {
        parameter("type") { view =>
          val instanceDetails = Future {
            val viewType = ViewType.toViewType(view)
            val siteNode = Site1.fromNeo4jGraph(siteId)
            siteNode match {
              case Some(site) =>
                val instanceFromSite = siteNode.get.instances.find(inst => if (inst.instanceId.nonEmpty) false else inst.instanceId.get.equals(instanceId))
                instanceFromSite match {
                  case Some(foundInstance) =>
                    val resultInstance = viewType match {
                      case OPERATIONS => filterInstanceViewOperations(foundInstance, ViewLevel.toViewLevel("DETAILED"))
                      case ARCHITECTURE => filterInstanceViewArchitecture(foundInstance, ViewLevel.toViewLevel("DETAILED"))
                      case LIST => filterInstanceViewList(foundInstance, ViewLevel.toViewLevel("DETAILED"))
                    }
                    Some(resultInstance)
                  case None =>
                    logger.warn(s"Unable to find instance with ID: $instanceId")
                    None
                }
              case None =>
                logger.warn(s"Failed while doing fromNeo4jGraph of Site for siteId : $siteId")
                None
            }
          }
          onComplete(instanceDetails) {
            case Success(successResponse) => complete(StatusCodes.OK, successResponse)
            case Failure(ex) =>
              logger.error(s"Unable to get Instance Details; Failed with ${ex.getMessage}", ex)
              complete(StatusCodes.BadRequest, "Unable to get Instance Details")
          }
        }

      }
    } ~ put {
      path("keypairs" / LongNumber) { siteId =>
        entity(as[Multipart.FormData]) { formData =>
          val uploadKeyPairsResult = Future {
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
            val sshKeyContentInfo = SSHKeyContentInfo(dataMap)
            //            val keyMaterials = sshKeyContentInfo.keyMaterials
            val siteNode = Site1.fromNeo4jGraph(siteId)
            siteNode match {
              case Some(site) =>
                val instanceFromSite = site.instances
                instanceFromSite.foreach {
                  instance =>
                    val accessInfo = instance.sshAccessInfo
                    val persistedKeyName = accessInfo.get.keyPair.keyName
                    var persistedSshUserName = accessInfo.get.userName

                    val keyMaterials = sshKeyContentInfo.keyMaterials

                    if (keyMaterials.nonEmpty) {
                      val keySetMap = keyMaterials.keySet
                      keySetMap.foreach { sshKeyMaterialEntry =>
                        if (!sshKeyMaterialEntry.equals("userName") && !sshKeyMaterialEntry.equals("passPhrase")) {
                          val sshKeyData = keyMaterials.get(sshKeyMaterialEntry)
                          if (persistedKeyName.equalsIgnoreCase(sshKeyMaterialEntry)) {
                            val keyDirPath = "/keys".concat(instance.instanceId.get).concat("/")
                            sshKeyData match {
                              case Some(keyData) =>
                                try {
                                  val keyFilePath = keyDirPath.concat(sshKeyMaterialEntry).concat(".pem")
                                  val temporaryFile = new File(keyDirPath)
                                  temporaryFile.mkdirs()
                                  val file = new File(keyFilePath)
                                  FileUtils.saveContentToFile(file.toString, sshKeyData.get)
                                  val userName = keyMaterials.filterKeys(key => key.equals("username") && !keyMaterials(key).equalsIgnoreCase("undefined")).get("username")

                                  userName match {
                                    case Some(userNameValue) =>
                                      persistedSshUserName = userName.toString
                                    case None =>
                                      persistedSshUserName = "ubuntu"
                                  }
                                  val passPhrase = keyMaterials.filterKeys(key => key.contains("passPhrase")).get("passPhrase")
                                  val keyPairInfo = accessInfo.get.keyPair
                                  val KeyPairInfoUpdated = KeyPairInfo(Some(1), keyPairInfo.keyName, keyPairInfo.keyFingerprint, sshKeyData.get, Some(keyFilePath), KeyPairStatus.toKeyPairStatus("UPLOADED"), Some(persistedSshUserName), Some(passPhrase.get))
                                  val sshAccessInfoNew = SSHAccessInfo(Some(1), KeyPairInfoUpdated, persistedSshUserName, accessInfo.get.port)
                                  val instanceObj = instance.copy(sshAccessInfo = Some(sshAccessInfoNew))
                                  //need to save instance OBJECT
                                } catch {
                                  case ex: Exception =>
                                    logger.error(s"Raised Exception :$ex")
                                    throw ex
                                }
                              case None => None
                            }

                          } else {
                            try {
                              //createNewSshAccessInfo
                              val keyDirPath = "/keys".concat(instance.instanceId.get).concat("/")
                              val userName = keyMaterials.filterKeys(key => key.equals("userName") && !keyMaterials(key).equalsIgnoreCase("undefined") && keyMaterials(key).nonEmpty).get("username")
                              userName match {
                                case Some(userNameValue) =>
                                  persistedSshUserName = userName.toString
                                case None =>
                                  persistedSshUserName = "ubuntu"
                              }
                              val keyFilePath = keyDirPath.concat(sshKeyMaterialEntry).concat(".pem")
                              val temporaryFile = new File(keyDirPath)
                              temporaryFile.mkdirs()
                              val file = new File(keyFilePath)
                              FileUtils.saveContentToFile(file.toString, sshKeyData.get)

                              val passPhrase = keyMaterials.filterKeys(key => key.equals("passPhrase") && !keyMaterials(key).equalsIgnoreCase("undefined") && keyMaterials(key).nonEmpty).get("passPhrase")
                              val KeyPairInfoUpdated = KeyPairInfo(Some(1), sshKeyMaterialEntry, None, sshKeyData.get, Some(keyFilePath), KeyPairStatus.toKeyPairStatus("UPLOADED"), Some(persistedSshUserName), Some(passPhrase.get))
                              val sshAccessInfoNew = SSHAccessInfo(Some(1), KeyPairInfoUpdated, persistedSshUserName, accessInfo.get.port)
                              val instanceObj = instance.copy(sshAccessInfo = Some(sshAccessInfoNew))
                              //need to save instance OBJECT
                            } catch {
                              case ex: Exception =>
                                logger.error(s"Raised Exception: $ex")
                                throw ex
                            }
                          }
                        } else {
                          None
                        }
                      }
                    } else {
                      None
                    }
                }
              case None =>
                throw new NotFoundException(s"Site Entity with ID : $siteId is Not Found")
            }
            Some("Uploaded Key pair Info successfully")
          }
          onComplete(uploadKeyPairsResult) {
            case Success(page) => complete(StatusCodes.OK, page)
            case Failure(ex) =>
              logger.error(s"Failed to update Keys, Message: ${ex.getMessage}", ex)
              complete(StatusCodes.BadRequest, s"Failed to update Keys")
          }
        }
      }
    }
  }

  val route: Route = discoveryRoutes ~ userRoute ~ keyPairRoute ~ catalogRoutes ~ appSettingServiceRoutes ~ apmServiceRoutes ~ nodeRoutes ~ appsettingRoutes

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

  def filterInstanceViewList(instance: Instance, viewLevel: ViewLevel): Instance = {
    viewLevel match {
      case SUMMARY =>
        Instance(instance.id, instance.instanceId, instance.name, instance.state, instance.instanceType, None, None, instance.publicDnsName,
          None, None, None, instance.tags, instance.sshAccessInfo, List.empty[InstanceConnection], List.empty[InstanceConnection],
          Set.empty[ProcessInfo], None, List.empty[InstanceUser], instance.account, instance.availabilityZone, None, instance.privateIpAddress,
          instance.publicIpAddress, None, None, None, List.empty[InstanceBlockDeviceMappingInfo], List.empty[SecurityGroupInfo],
          instance.reservedInstance, instance.region)
      case DETAILED =>
        Instance(instance.id, instance.instanceId, instance.name, instance.state, instance.instanceType, None, None, instance.publicDnsName,
          None, instance.memoryInfo, instance.rootDiskInfo, instance.tags, None, List.empty[InstanceConnection], List.empty[InstanceConnection],
          Set.empty[ProcessInfo], instance.image, List.empty[InstanceUser], instance.account, instance.availabilityZone, instance.privateDnsName, instance.privateIpAddress,
          None, instance.elasticIP, instance.monitoring, instance.rootDeviceType, List.empty[InstanceBlockDeviceMappingInfo], instance.securityGroups,
          instance.reservedInstance, instance.region)
    }
  }

  def filterInstanceViewArchitecture(instance: Instance, viewLevel: ViewLevel): Instance = {
    viewLevel match {
      case SUMMARY =>
        Instance(instance.id, instance.instanceId, instance.name, instance.state, instance.instanceType, None, None, instance.publicDnsName,
          None, None, None, instance.tags, instance.sshAccessInfo, instance.liveConnections, List.empty[InstanceConnection],
          Set.empty[ProcessInfo], None, List.empty[InstanceUser], instance.account, instance.availabilityZone, None, instance.privateIpAddress,
          instance.publicIpAddress, None, None, None, List.empty[InstanceBlockDeviceMappingInfo], List.empty[SecurityGroupInfo],
          instance.reservedInstance, instance.region)
      case DETAILED =>
        Instance(instance.id, instance.instanceId, instance.name, instance.state, instance.instanceType, None, None, instance.publicDnsName,
          None, instance.memoryInfo, instance.rootDiskInfo, instance.tags, None, List.empty[InstanceConnection], List.empty[InstanceConnection],
          instance.processes, instance.image, List.empty[InstanceUser], instance.account, instance.availabilityZone, instance.privateDnsName, instance.privateIpAddress,
          None, instance.elasticIP, instance.monitoring, instance.rootDeviceType, List.empty[InstanceBlockDeviceMappingInfo], instance.securityGroups,
          instance.reservedInstance, instance.region)
    }
  }

  def filterInstanceViewOperations(instance: Instance, viewLevel: ViewLevel): Instance = {
    viewLevel match {
      case SUMMARY =>
        Instance(instance.id, instance.instanceId, instance.name, instance.state, instance.instanceType, None, None, instance.publicDnsName,
          None, None, None, instance.tags, instance.sshAccessInfo, instance.liveConnections, instance.estimatedConnections,
          Set.empty[ProcessInfo], None, List.empty[InstanceUser], instance.account, instance.availabilityZone, None, instance.privateIpAddress,
          instance.publicIpAddress, None, None, None, List.empty[InstanceBlockDeviceMappingInfo], List.empty[SecurityGroupInfo],
          instance.reservedInstance, instance.region)
      case DETAILED =>
        Instance(instance.id, instance.instanceId, instance.name, instance.state, instance.instanceType, None, None, instance.publicDnsName,
          None, instance.memoryInfo, instance.rootDiskInfo, instance.tags, None, List.empty[InstanceConnection], List.empty[InstanceConnection],
          instance.processes, instance.image, List.empty[InstanceUser], instance.account, instance.availabilityZone, instance.privateDnsName, instance.privateIpAddress,
          None, instance.elasticIP, instance.monitoring, instance.rootDeviceType, instance.blockDeviceMappings, instance.securityGroups,
          instance.reservedInstance, instance.region)
    }
  }
}