package com.imaginea.activegrid.core.models

import org.neo4j.graphdb.Node

import scala.collection.JavaConversions._

/**
  * Created by nagulmeeras on 27/10/16.
  */
case class SecurityGroupInfo(override val id: Option[Long],
                             groupName: Option[String],
                             groupId: Option[String],
                             ownerId: Option[String],
                             description: Option[String],
                             ipPermissions: List[IpPermissionInfo],
                             tags: List[KeyValueInfo]) extends BaseEntity

object SecurityGroupInfo {
  def appply(id: Option[Long]): SecurityGroupInfo = {
    SecurityGroupInfo(id, None, None, None, None, List.empty[IpPermissionInfo], List.empty[KeyValueInfo])
  }

  val repository = Neo4jRepository
  val securityGroupInfoLabel = "SecurityGroupInfo"
  val securityGroup_IpPermission_Relation = "HAS_IP_PERMISSION"
  val securityGroup_KeyValue_Relation = "HAS_KEY_VALUE"

  implicit class SecurityGroupInfoImpl(securityGroupInfo: SecurityGroupInfo) extends Neo4jRep[SecurityGroupInfo] {
    override def toNeo4jGraph(entity: SecurityGroupInfo): Node = {
      repository.withTx {
        neo =>
          val node = repository.createNode(securityGroupInfoLabel)(neo)
          if (entity.groupName.nonEmpty) node.setProperty("groupName", entity.groupName)
          if (entity.groupId.nonEmpty) node.setProperty("groupId", entity.groupId)
          if (entity.ownerId.nonEmpty) node.setProperty("ownerId", entity.ownerId)
          if (entity.description.nonEmpty) node.setProperty("description", entity.description)
          entity.ipPermissions.foreach {
            ipPermission =>
              val ipPermissionNode = ipPermission.toNeo4jGraph(ipPermission)
              repository.createRelation(securityGroup_IpPermission_Relation, node, ipPermissionNode)
          }
          entity.tags.foreach {
            tag =>
              val tagNode = tag.toNeo4jGraph(tag)
              repository.createRelation(securityGroup_KeyValue_Relation, node, tagNode)
          }
          node
      }
    }

    override def fromNeo4jGraph(nodeId: Long): Option[SecurityGroupInfo] = {
      SecurityGroupInfo.fromNeo4jGraph(nodeId)
    }
  }

  def fromNeo4jGraph(nodeId: Long): Option[SecurityGroupInfo] = {
    repository.withTx {
      neo =>
        val node = repository.getNodeById(nodeId)(neo)
        if (repository.hasLabel(node, securityGroupInfoLabel)) {
          val tupleObj = node.getRelationships.foldLeft(Tuple2[List[IpPermissionInfo], List[KeyValueInfo]](List.empty[IpPermissionInfo], List.empty[KeyValueInfo])) {
            (tuple, relationship) =>
              val childNode = relationship.getEndNode
              relationship.getType.name match {
                case `securityGroup_KeyValue_Relation` => Tuple2(tuple._1, tuple._2.::(KeyValueInfo.fromNeo4jGraph(childNode.getId).get))
                case `securityGroup_IpPermission_Relation` => Tuple2(tuple._1.::(IpPermissionInfo.fromNeo4jGraph(childNode.getId).get), tuple._2)
              }
          }
          Some(SecurityGroupInfo(Some(nodeId),
            repository.getProperty[String](node, "groupName"),
            repository.getProperty[String](node, "groupId"),
            repository.getProperty[String](node, "ownerId"),
            repository.getProperty[String](node, "description"),
            tupleObj._1,
            tupleObj._2))
        } else {
          None
        }
    }
  }
}
