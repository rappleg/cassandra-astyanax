/*
 * Copyright 2012 ReachLocal Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reachlocal.grails.plugins.cassandra.astyanax

import org.codehaus.groovy.grails.commons.ConfigurationHolder
import com.netflix.astyanax.thrift.ThriftFamilyFactory
import com.netflix.astyanax.connectionpool.impl.CountingConnectionPoolMonitor
import com.netflix.astyanax.connectionpool.impl.ConnectionPoolConfigurationImpl
import com.netflix.astyanax.impl.AstyanaxConfigurationImpl
import com.netflix.astyanax.AstyanaxContext
import com.netflix.astyanax.serializers.StringSerializer
import com.netflix.astyanax.model.ColumnFamily
import com.netflix.astyanax.util.RangeBuilder
import groovy.sql.Sql
import org.springframework.beans.factory.InitializingBean

/**
 * @author Bob Florian
 *
 */
class AstyanaxService implements InitializingBean
{
	boolean transactional = false

	def clusters = ConfigurationHolder.config.astyanax.clusters
	/*
	def port = ConfigurationHolder.config?.cassandra?.port ?: 9160
	def host = ConfigurationHolder.config?.cassandra?.host ?: "localhost"
	def seeds = ConfigurationHolder.config?.cassandra?.seeds ?: "${host}:${port}"
	def maxConsPerHost = ConfigurationHolder.config?.cassandra?.maxConsPerHost ?: 10
	def cluster = ConfigurationHolder.config?.cassandra?.cluster ?: "Test Cluster"
	def connectionPoolName = ConfigurationHolder.config?.cassandra?.connectionPoolName ?: "MyConnectionPool"
	def discoveryType = ConfigurationHolder.config?.cassandra?.discoveryType ?: com.netflix.astyanax.connectionpool.NodeDiscoveryType.NONE
     */

	String defaultCluster = ConfigurationHolder.config?.astyanax?.defaultCluster ?: "standard"
	String defaultKeyspace = ConfigurationHolder.config?.astyanax?.defaultKeySpace ?: "AstyanaxTest"
	String cqlDriver = "org.apache.cassandra.cql.jdbc.CassandraDriver"

	private clusterMap = [:]
	void afterPropertiesSet ()
	{
		clusters.each {key, props ->
			clusterMap[key] = [
					connectionPoolConfiguration:  new ConnectionPoolConfigurationImpl(props.connectionPoolName)
							.setPort(props.port)
							.setMaxConnsPerHost(props.maxConsPerHost)
							.setSeeds(props.seeds),

					contexts: [:]
			]
		}
	}

	/**
	 * Returns a keyspace entity
	 *
	 * @param name
	 * @return
	 */
	def keyspace(String name=defaultKeyspace, String cluster=defaultCluster)
	{
		context(name).entity
	}

	/**
	 * Constructs an Astyanax context and passed execution to a closure
	 *
	 * @param keyspace name of the keyspace
	 * @param block closure to be executed
	 * @throws Exception
	 */
	def withKeyspace(String keyspace=defaultKeyspace, String cluster=defaultCluster, Closure block) throws Exception
	{
		block(context(keyspace).entity)
	}

	/**
	 * Initialized a CQL JDBC connection
	 * 
	 * @param keyspace name of the keyspace
	 * @return initialized JDBC/CQL connection object
	 * @throws Exception
	 */
	Sql cql(String keyspace=defaultKeyspace, String cluster=defaultCluster) throws Exception
	{
		def seed = clusters[cluster].seeds[0]
		Sql.newInstance("jdbc:cassandra://${seed}/${keyspace}", cqlDriver)
	}

	/**
	 * Utility method to print out readable version of column family for debugging purposes
	 *
	 * @param names list of column family names to display
	 * @param keyspace name of the keyspace
	 * @param maxRows the maximum number of rows to print
	 * @param maxColumns the maximum number of columns to print for each row
	 * @param out the print writer to use, defaults to System.out
	 */
	void showColumnFamilies (Collection names, String keyspace, String cluster=defaultCluster, Integer maxRows=50, Integer maxColumns=10, out=System.out) {
		names.each {String cf ->
			withKeyspace(keyspace) {ks ->
				out.println "${cf}:"
				ks.prepareQuery(new ColumnFamily(cf, StringSerializer.get(), StringSerializer.get()))
						.getKeyRange(null,null,'0','0',maxRows)
						.withColumnRange(new RangeBuilder().setMaxSize(maxColumns).build())
						.execute()
						.result.each{row ->

					out.println "    ${row.key} =>"
					row.columns.each {col ->
						try {
							out.println "        ${col.name} => '${col.stringValue}'"
						}
						catch (Exception ex) {
							out.println "        ${col.name} => ${col.longValue}"
						}
					}
				}
				out.println""
			}
		}
	}

	/**
	 * Provides persistence methods for cassandra-orm plugin
	 */
	def orm = new AstyanaxPersistenceMethods()
	
	def context(keyspace, cluster="standard")
	{
		def context = clusterMap[cluster].contexts[keyspace]
		if (!context) {
			context = newContext(keyspace, cluster)
			context.start()
		}
		return context
	}
	
	private synchronized newContext(keyspace, cluster)
	{
		def entry = clusterMap[cluster]
		def context = entry.contexts[keyspace]
		if (!context) {
			def props = clusters[cluster]
			context = new AstyanaxContext.Builder()
					.forCluster(cluster)
					.forKeyspace(keyspace)
					.withAstyanaxConfiguration(new AstyanaxConfigurationImpl().setDiscoveryType(props.discoveryType))
					.withConnectionPoolConfiguration(entry.connectionPoolConfiguration)
					.withConnectionPoolMonitor(new CountingConnectionPoolMonitor())
					.buildKeyspace(ThriftFamilyFactory.getInstance());

			entry.contexts[keyspace] = context
		}
		return context
	}
}
