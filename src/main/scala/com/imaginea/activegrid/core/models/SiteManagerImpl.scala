package com.imaginea.activegrid.core.models

/**
  * Created by sivag on 3/11/16.
  */
object SiteManagerImpl {

  def deleteIntanceFromSite(siteId: Long, instanceId: String): Boolean = {
    val siteNode = Site1.fromNeo4jGraph(siteId)
    siteNode.map { site =>
      //val instance = site.instances.map(instance => instance.id.toString == instanceId)

      //Removing instance  from groups list
      site.groupsList.foreach(instanceGroup => Neo4jRepository.deleteRelation(instanceId, instanceGroup, "instances"))
      //Need to remove from application.


      //Removing from site
      Neo4jRepository.deleteRelation(instanceId, site, "instances")
    }
    siteNode.isDefined
  }

  def deletePolicy(policyId: String): Boolean = {
    val mayBePolicy = Neo4jRepository.findNodeById(policyId.toLong)
    mayBePolicy.map {
      policyNode => policyNode.delete()
    }
    mayBePolicy.isDefined
  }

}