package com.imaginea.activegrid.core.models

import com.typesafe.scalalogging.Logger
import org.neo4j.graphdb.Node
import org.slf4j.LoggerFactory

/**
  * Created by sivag on 28/10/16.
  */
case class Application(override val id: Option[Long],
                       name: String,
                       description: String,
                       version: String,
                       instaces: List[Instance],
                       software: Software,
                       tiers: List[ApplicationTier],
                       aPMServerDetails: APMServerDetails,
                       responseTime: Double) extends BaseEntity

object Application {

  implicit class ApplicationImpl(application: Application) extends Neo4jRep[Application] {
    val logger = Logger(LoggerFactory.getLogger(getClass.getName))
    val label = "Application"

    override def toNeo4jGraph(application: Application): Node = {
      logger.debug(s"In toGraph for Software: $application")
      val map = Map("name" -> application.name,
        "description" -> application.description,
        "version" -> application.version,
        "instaces" -> application.instaces.toArray,
        "software" -> application.software,
        "tiers" -> application.tiers.toArray,
        "aPMServerDetails" -> application.aPMServerDetails,
        "responseTime" -> application.responseTime
      )

      val applicationNode = GraphDBExecutor.saveEntity[Application](label, map)
      applicationNode
    }

    override def fromNeo4jGraph(nodeId: Long): Option[Application] = {
      Application.fromNeo4jGraph(nodeId)
    }

  }

  def fromNeo4jGraph(nodeId: Long): Option[Application] = {
    val logger = Logger(LoggerFactory.getLogger(getClass.getName))
    try {
      val node = GraphDBExecutor.findNodeById(nodeId)
      val map = GraphDBExecutor.getProperties(node.get, "version", "name", "provider", "downloadURL", "port", "processNames", "discoverApplications")
      val application = Application(Some(nodeId),
        map("name").asInstanceOf[String],
        map("description").asInstanceOf[String],
        map("version").asInstanceOf[String],
        map("instaces").asInstanceOf[Array[Instance]].toList,
        map("software").asInstanceOf[Software],
        map("tiers").asInstanceOf[Array[ApplicationTier]].toList,
        map("aPMServerDetails").asInstanceOf[APMServerDetails],
        map("responseTime").asInstanceOf[Double])
      Some(application)
    } catch {
      case ex: Exception =>
        logger.warn(ex.getMessage, ex)
        None

    }
  }
}