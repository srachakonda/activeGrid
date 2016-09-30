package com.activegrid.models

import org.neo4j.graphdb.Node
import org.neo4j.kernel.impl.core.NodeProxy

/**
  * Created by nagulmeeras on 27/09/16.
  */

case class Setting(key: String, value: String) extends BaseEntity with Neo4jRep[Setting]
