/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.neo4j.helpers.collection.IteratorUtil.asSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.ThreadToStatementContextBridge;
import org.neo4j.kernel.api.KernelAPI;
import org.neo4j.kernel.api.StatementContext;
import org.neo4j.kernel.api.TransactionContext;
import org.neo4j.test.ImpermanentGraphDatabase;

public class ITKernelAPI
{
    /**
     * While we transition ownership from the Beans API to the Kernel API for core database
     * interactions, there will be a bit of a mess. Our first goal is an architecture like this:
     *
     *         Users
     *        /    \
     *  Beans API   Cypher
     *        \    /
     *      Kernel API
     *           |
     *  Kernel Implementation
     *
     * But our current intermediate architecture looks like this:
     *
     *           Users
     *        /        \
     *  Beans API <--- Cypher
     *     |    \    /
     *     |  Kernel API
     *     |      |
     *  Kernel Implementation
     *
     * Meaning Kernel API and Beans API both manipulate the underlying kernel, causing lots of corner cases. Most
     * notably, those corner cases are related to Transactions, and the interplay between three transaction APIs:
     *   - The Beans API
     *   - The JTA Transaction Manager API
     *   - The Kernel TransactionContext API
     *
     * In the long term, the goal is for JTA compliant stuff to live outside of the kernel, as an addon. The Kernel
     * API will rule supreme over the land of transactions. We are a long way away from there, however, so as a first
     * intermediary step, the JTA transaction manager rules supreme, and the Kernel API piggybacks on it.
     *
     * This test shows us how to use both the Kernel API and the Beans API together in the same transaction,
     * during the transition phase.
     */
    @Test
    public void mixingBeansApiWithKernelAPI() throws Exception
    {
        ThreadToStatementContextBridge statementContextProvider = db.getDependencyResolver().resolveDependency(
                ThreadToStatementContextBridge.class );

        // 1: Start your transactions through the Beans API
        Transaction beansAPITx = db.beginTx();

        // 2: Get a hold of a KernelAPI statement context for the *current* transaction this way:
        StatementContext statement = statementContextProvider.getCtxForWriting();

        // 3: Now you can interact through both the statement context and the kernel API to manipulate the
        //    same transaction.
        Node node = db.createNode();

        long labelId = statement.getOrCreateLabelId( "labello" );
        statement.addLabelToNode( labelId, node.getId() );

        // 4: Commit through the beans API
        beansAPITx.success();
        beansAPITx.finish();

        // NOTE: Transactions are still thread-bound right now, because we use JTA to "own" transactions, meaning if you use
        // both the Kernel API to create transactions while a Beans API transaction is running in the same
        // thread, the results are undefined.

        // When the Kernel API implementation is done, the Kernel API transaction implementation is not meant
        // to be bound to threads.
    }

    @Test
    public void mixingBeansApiWithKernelAPIForNestedTransaction() throws Exception
    {
        // GIVEN
        KernelAPI kernel = db.getDependencyResolver().resolveDependency( KernelAPI.class );
        Transaction outerTx = db.beginTx();
        TransactionContext tx = kernel.newTransactionContext();
        StatementContext statement = tx.newStatementContext();

        // WHEN
        Node node = db.createNode();
        long labelId = statement.getOrCreateLabelId( "labello" );
        statement.addLabelToNode( labelId, node.getId() );
        statement.close( true );
        tx.success();
        tx.finish();
        outerTx.finish();
    }
    
    @Test
    public void changesInTransactionContextShouldBeRolledBackWhenTxIsRolledBack() throws Exception
    {
        // GIVEN
        ThreadToStatementContextBridge statementContextProvider = db.getDependencyResolver().resolveDependency(
                ThreadToStatementContextBridge.class );

        Transaction tx = db.beginTx();
        StatementContext statement = statementContextProvider.getCtxForWriting();

        // WHEN
        Node node = db.createNode();
        long labelId = statement.getOrCreateLabelId( "labello" );
        statement.addLabelToNode( labelId, node.getId() );
        //statement.close();  // Because we are currently using the statement context from the beans API, the Beans API will close it for us
        tx.finish();

        // THEN
        statement = statementContextProvider.getCtxForReading();
        assertFalse( statement.isLabelSetOnNode( labelId, node.getId() ) );
    }
    
    @Test
    public void shouldNotBeAbleToCommitIfFailedTransactionContext() throws Exception
    {
        ThreadToStatementContextBridge statementContextProvider = db.getDependencyResolver().resolveDependency(
                ThreadToStatementContextBridge.class );

        Transaction tx = db.beginTx();
        StatementContext statement = statementContextProvider.getCtxForWriting();

        // WHEN
        Node node = db.createNode();
        long labelId = statement.getOrCreateLabelId( "labello" );
        statement.addLabelToNode( labelId, node.getId() );
        //statement.close();  // Because we are currently using the statement context from the beans API, the Beans API will close it for us
        tx.failure();
        tx.success();
        tx.finish();

        // THEN
        statement = statementContextProvider.getCtxForReading();
        assertFalse( statement.isLabelSetOnNode( labelId, node.getId() ) );
    }

    @Test
    public void transactionStateShouldRemovePreviouslyAddedLabel() throws Exception
    {
        ThreadToStatementContextBridge statementContextProvider = db.getDependencyResolver().resolveDependency(
                ThreadToStatementContextBridge.class );

        Transaction tx = db.beginTx();
        StatementContext statement = statementContextProvider.getCtxForWriting();

        // WHEN
        Node node = db.createNode();
        long labelId1 = statement.getOrCreateLabelId( "labello1" );
        long labelId2 = statement.getOrCreateLabelId( "labello2" );
        statement.addLabelToNode( labelId1, node.getId() );
        statement.addLabelToNode( labelId2, node.getId() );
        statement.removeLabelFromNode( labelId2, node.getId() );
        tx.success();
        tx.finish();

        // THEN
        statement = statementContextProvider.getCtxForReading();
        assertEquals( asSet( labelId1 ), asSet( statement.getLabelsForNode( node.getId() ) ) );
    }
    
    @Test
    public void transactionStateShouldReflectRemovingAddedLabelImmediately() throws Exception
    {
        ThreadToStatementContextBridge statementContextProvider = db.getDependencyResolver().resolveDependency(
                ThreadToStatementContextBridge.class );

        Transaction tx = db.beginTx();
        StatementContext statement = statementContextProvider.getCtxForWriting();

        // WHEN
        Node node = db.createNode();
        long labelId1 = statement.getOrCreateLabelId( "labello1" );
        long labelId2 = statement.getOrCreateLabelId( "labello2" );
        statement.addLabelToNode( labelId1, node.getId() );
        statement.addLabelToNode( labelId2, node.getId() );
        statement.removeLabelFromNode( labelId2, node.getId() );

        // THEN
        assertFalse( statement.isLabelSetOnNode( labelId2, node.getId() ) );
        assertEquals( asSet( labelId1 ), asSet( statement.getLabelsForNode( node.getId() ) ) );

        tx.success();
        tx.finish();
    }

    @Test
    public void transactionStateShouldReflectRemovingLabelImmediately() throws Exception
    {
        // GIVEN
        ThreadToStatementContextBridge statementContextProvider = db.getDependencyResolver().resolveDependency(
                ThreadToStatementContextBridge.class );
        Transaction tx = db.beginTx();
        StatementContext statement = statementContextProvider.getCtxForWriting();
        Node node = db.createNode();
        long labelId1 = statement.getOrCreateLabelId( "labello1" );
        long labelId2 = statement.getOrCreateLabelId( "labello2" );
        statement.addLabelToNode( labelId1, node.getId() );
        statement.addLabelToNode( labelId2, node.getId() );
        tx.success();
        tx.finish();
        tx = db.beginTx();
        statement = statementContextProvider.getCtxForWriting();

        // WHEN
        statement.removeLabelFromNode( labelId2, node.getId() );

        // THEN
        assertFalse( statement.isLabelSetOnNode( labelId2, node.getId() ) );
        assertEquals( asSet( labelId1 ), asSet( statement.getLabelsForNode( node.getId() ) ) );
        tx.success();
        tx.finish();
    }

    private GraphDatabaseAPI db;
    
    @Before
    public void before() throws Exception
    {
        db = new ImpermanentGraphDatabase();
    }

    @After
    public void after() throws Exception
    {
        db.shutdown();
    }
}
