package com.imaginea.activegrid.core.models

/**
  * Created by nagulmeeras on 25/10/16.
  */

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.regions.{Region, RegionUtils}
import com.amazonaws.services.ec2.model._
import com.amazonaws.services.ec2.{AmazonEC2, AmazonEC2Client, model}
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.collection.immutable.List

object AWSComputeAPI {
  val logger = Logger(LoggerFactory.getLogger(getClass.getName))

  def getInstances(amazonEC2: AmazonEC2 , accountInfo: AccountInfo): List[Instance] = {

    val awsInstancesResult = getAWSInstances(amazonEC2)
    val totalsecurityGroups = amazonEC2.describeSecurityGroups.getSecurityGroups.foldLeft(Map[String, SecurityGroup]())((map, sg) => map + ((sg.getGroupId, sg)))
    val addresses = amazonEC2.describeAddresses.getAddresses.foldLeft(Map[String, Address]())((map, address) => map + ((address.getInstanceId, address)))
    val imageIds = awsInstancesResult.foldLeft(List[String]())((list, awsInstance) => list.::(awsInstance.getImageId))
    val imagesMap = getImageInformation(amazonEC2, imageIds)

    //val imageInfoMap = getImageInfoMap(imagesMap )

    val instances: List[Instance] = awsInstancesResult.map {
      awsInstance =>
        val imageInfo = getImageInfo(awsInstance.getImageId, imagesMap)
        val instanceSecurityGroups = awsInstance.getSecurityGroups
        val securityGroupInfos = getSecurityGroupInfo(totalsecurityGroups, instanceSecurityGroups.toList)
        val instance = new Instance(None,
          Some(awsInstance.getInstanceId),
          awsInstance.getKeyName,
          Some(awsInstance.getState.toString),
          Option(awsInstance.getInstanceType),
          Option(awsInstance.getPlatform),
          Option(awsInstance.getArchitecture),
          Option(awsInstance.getPublicDnsName),
          Option(awsInstance.getLaunchTime.getTime),
          Some(StorageInfo(None, 0D, AWSInstanceType.toAWSInstanceType(awsInstance.getInstanceType).ramSize)),
          Some(StorageInfo(None, 0D, AWSInstanceType.toAWSInstanceType(awsInstance.getInstanceType).rootPartitionSize)),
          awsInstance.getTags.map(tag => KeyValueInfo(None, tag.getKey, tag.getValue)).toList,
          createSSHAccessInfo(awsInstance.getKeyName),
          List.empty,
          List.empty,
          Set.empty,
          Some(imageInfo),
          List.empty,
          Option(accountInfo),
          Option(awsInstance.getPlacement.getAvailabilityZone),
          Option(awsInstance.getPrivateDnsName),
          Option(awsInstance.getPrivateIpAddress),
          Option(awsInstance.getPublicIpAddress),
          Option(addresses(awsInstance.getInstanceId).getPublicIp),
          Option(awsInstance.getMonitoring.getState),
          Option(awsInstance.getRootDeviceType),
          null, //TODO need merge the code of Blocked Device Mappings from Naveed
          securityGroupInfos,
          false,
          Option(accountInfo.regionName)
        )
        logger.debug(s"Instance OBJ:$instance")
        instance
    }
    instances
  }

  private def getAWSCredentials(builder: AWSContextBuilder): AWSCredentials = {
    new AWSCredentials() {
      def getAWSAccessKeyId: String = {
        builder.accessKey
      }

      def getAWSSecretKey: String = {
        builder.secretKey
      }
    }
  }

  def AWSInstanceHelper(credentials: AWSCredentials, region: Region): AmazonEC2 = {
    val amazonEC2: AmazonEC2 = new AmazonEC2Client(credentials)
    amazonEC2.setRegion(region)
    amazonEC2
  }

  def getAWSInstances(amazonEC2: AmazonEC2): List[model.Instance] = {
    amazonEC2.describeInstances.getReservations.toList.flatMap {
      reservation => reservation.getInstances
    }
  }

  def getImageInformation(amazonEC2: AmazonEC2, imageIds: List[String]): Map[String, Image] = {
    val describeImagesRequest = new DescribeImagesRequest
    describeImagesRequest.setImageIds(imageIds)
    val imagesResult = amazonEC2.describeImages(describeImagesRequest)
    imagesResult.getImages.foldLeft(Map[String, Image]())((imageMap, image) => imageMap + ((image.getImageId, image)))
  }

  def getImageInfoMap(imagesMap: Map[String, Image]): Map[String, ImageInfo] = {
    imagesMap.foldLeft(Map[String, ImageInfo]())((imageInfoMap, image) => imageInfoMap + ((image._1, ImageInfo.fromNeo4jGraph(image._1.toLong).get)))
  }

  def getImageInfo(imageId: String, imagesMap: Map[String, Image]): ImageInfo = {
    logger.info(s"Image ID: $imageId")
    if (imageId.nonEmpty) {

      if (imagesMap.contains(imageId)) {
        val image = imagesMap(imageId)
        ImageInfo(None,
          imageId,
          Option(image.getState),
          Option(image.getOwnerId),
          image.getPublic,
          Option(image.getArchitecture),
          Option(image.getImageType),
          Option(image.getPlatform),
          Option(image.getImageOwnerAlias),
          Option(image.getName),
          Option(image.getDescription),
          Option(image.getRootDeviceType),
          Option(image.getRootDeviceName),
          Some(""))
      } else {
        ImageInfo(None, imageId, None, None, publicValue = false, None, None, None, None, None, None, None, None, None)
      }
    } else {
      ImageInfo(None, imageId, None, None, publicValue = false, None, None, None, None, None, None, None, None, None)
    }
  }

  def createSSHAccessInfo(keyName: String): Option[SSHAccessInfo] = {
    val node = GraphDBExecutor.getNodeByProperty("KeyPairInfo", "keyName", keyName)
    val keyPairInfo = node.flatMap { node => KeyPairInfo.fromNeo4jGraph(node.getId) }
    keyPairInfo.flatMap(info => Some(SSHAccessInfo(None, info, "", 0)))
  }

  def getSecurityGroupInfo(totalSecurityGroups: Map[String, SecurityGroup], instanceGroupIdentifiers: List[GroupIdentifier]): List[SecurityGroupInfo] = {
    totalSecurityGroups.map {
      securityGroup =>
        if (totalSecurityGroups.contains(securityGroup._2.getGroupId)) {
          val sg = securityGroup._2
          val ipPermissions = sg.getIpPermissions.toList.map {
            ipPermission =>
              IpPermissionInfo(None,
                ipPermission.getFromPort,
                ipPermission.getToPort,
                IpProtocol.toProtocol(ipPermission.getIpProtocol),
                ipPermission.getUserIdGroupPairs.map {
                  pair => pair.getGroupId
                }.toSet, ipPermission.getIpRanges.toList)
          }
          SecurityGroupInfo(None,
            Option(sg.getGroupName),
            Option(securityGroup._1),
            Option(sg.getOwnerId),
            Option(sg.getDescription),
            ipPermissions,
            sg.getTags.map(tag => KeyValueInfo(None, tag.getKey, tag.getValue)).toList
          )
        } else {
          SecurityGroupInfo.appply(None)
        }
    }.toList
  }
  def getComputeAPI(accountInfo: AccountInfo): AmazonEC2 ={
    val region = RegionUtils.getRegion(accountInfo.regionName)
    val aWSContextBuilder = AWSContextBuilder(accountInfo.accessKey, accountInfo.secretKey, accountInfo.regionName)
    val aWSCredentials1 = getAWSCredentials(aWSContextBuilder)
    AWSInstanceHelper(aWSCredentials1, region)
  }

  def getReservedInstances(amazonEC2: AmazonEC2) : List[ReservedInstanceDetails] = {
    amazonEC2.describeReservedInstances.getReservedInstances.foldLeft(List[ReservedInstanceDetails]()){
      (list , reservedInstance) =>
        list.::(ReservedInstanceDetails(
          None,
          Option(reservedInstance.getInstanceType),
          Option(reservedInstance.getReservedInstancesId),
          Option(reservedInstance.getAvailabilityZone),
          Option(reservedInstance.getInstanceTenancy),
          Option(reservedInstance.getOfferingType),
          Option(reservedInstance.getProductDescription),
          Option(reservedInstance.getInstanceCount)
        ))
    }
  }

}