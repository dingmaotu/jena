/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jena.graph;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.After;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xenei.junit.contract.Contract;
import org.xenei.junit.contract.ContractTest;

import static org.junit.Assert.*;

import org.apache.jena.graph.Capabilities;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphStatisticsHandler;
import org.apache.jena.graph.GraphUtil;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.LiteralLabelFactory;
import org.apache.jena.mem.TrackingTripleIterator;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.impl.ReifierStd;
import org.apache.jena.shared.ClosedException;
import org.apache.jena.shared.DeleteDeniedException;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.testing_framework.AbstractGraphProducer;
import org.apache.jena.testing_framework.ContractTemplate;
import org.apache.jena.testing_framework.NodeCreateUtils;
import org.apache.jena.util.iterator.ClosableIterator;
import org.apache.jena.util.iterator.ExtendedIterator;

import static org.apache.jena.testing_framework.GraphHelper.*;

/**
 * Graph contract test.
 */
@Contract(Graph.class)
public class GraphContractTest<T extends Graph> extends
		ContractTemplate<AbstractGraphProducer<T>> {

	private static final Logger LOG = LoggerFactory
			.getLogger(GraphContractTest.class);

	protected RecordingGraphListener GL = new RecordingGraphListener();

	@Contract.Inject
	public final void setGraphContractTestProducer(
			AbstractGraphProducer<T> graphProducer) {
		super.setProducer(graphProducer);
	}

	@After
	public final void afterGraphContractTest() {
		getProducer().cleanUp();
		GL.clear();
	}

	@ContractTest
	public void testAdd_Triple() {
		Graph graph = getProducer().newInstance();
		graph.getEventManager().register(GL);
		txnBegin(graph);
		graph.add(triple("S P O"));
		txnCommit(graph);
		GL.assertHasStart("add", graph, triple("S P O"));
		assertTrue("Graph should contain <S P O>",
				graph.contains(triple("S P O")));
	}

	/**
	 * Inference graphs can not be truly empty.
	 * 
	 * @param g
	 * @param b
	 */
	private void assertEmpty(Graph g, Graph b) {
		if (b.isEmpty()) {
			assertTrue("Graph should be empty", g.isEmpty());
		} else {
			assertEquals("Graph should be in base state", b.find(Triple.ANY)
					.toList(), g.find(Triple.ANY).toList());
		}
	}

	/**
	 * Inference graphs can not be truly empty
	 * 
	 * @param g
	 * @param b
	 */
	private void assertNotEmpty(Graph g, Graph b) {
		if (b.isEmpty()) {
			assertFalse("Graph not should be empty", g.isEmpty());
		} else {
			assertNotEquals("Graph should not be in base state",
					b.find(Triple.ANY).toList(), g.find(Triple.ANY).toList());
		}
	}

	/**
	 * Test that clear works, in the presence of inferencing graphs that mean
	 * emptyness isn't available. This is why we go round the houses and test
	 * that expected ~= initialContent + addedStuff - removed - initialContent.
	 */
	@ContractTest
	public void testClear() {
		Graph graph = getProducer().newInstance();
		Graph base = copy(graph);

		graph.getEventManager().register(GL);
		txnBegin(graph);
		graph.clear();
		txnCommit(graph);
		assertEmpty(graph, base);
		GL.assertHasStart("someEvent", graph, GraphEvents.removeAll);
		GL.clear();

		// test after adding
		graph = graphWith(getProducer().newInstance(),
				"S P O; S e:ff 27; _1 P P3; S4 P4 'en'");
		graph.getEventManager().register(GL);
		txnBegin(graph);
		graph.clear();
		txnCommit(graph);
		assertEmpty(graph, base);
		if (GL.contains("delete")) {
			// deletes are listed -- ensure all deletes are listed
			GL.assertContains("delete", graph, triple("S P O"));
			GL.assertContains("delete", graph, triple("S e:ff 27"));
			GL.assertContains("delete", graph, triple("_1 P P3"));
			GL.assertContains("delete", graph, triple("S4 P4 'en'"));
		}
		GL.assertHasEnd("someEvent", graph, GraphEvents.removeAll);
		GL.clear();

	}

	@ContractTest
	public void testClose() {
		Graph graph = graphWith(getProducer().newInstance(),
				"S P O; S P2 O2; S3 P P3");
		graph.getEventManager().register(GL);
		assertFalse("Graph was constructed closed", graph.isClosed());

		graph.close();
		assertTrue("Graph should be closed", graph.isClosed());

		// exception may be thrown on begin or on execution.
		try {
			txnBegin(graph);
			try {
				graph.add(triple("S P O"));
				fail("added when closed");
			} catch (Exception expected) {
				GL.assertEmpty();
				// expected
			} finally {
				txnRollback(graph);
			}
		} catch (Exception expected) {
			GL.assertEmpty();
			// expected
		}

		try {
			txnBegin(graph);
			try {
				graph.delete(triple("x R y"));
				fail("delete when closed");
			} catch (ClosedException c) {
				// Expected
			} finally {
				txnRollback(graph);
				GL.assertEmpty();
			}
		} catch (Exception expected) {
			GL.assertEmpty();
			// expected
		}

		try {
			txnBegin(graph);

			try {
				graph.add(triple("x R y"));
				fail("add when closed");
			} catch (ClosedException c) { /* as required */
			} finally {
				txnRollback(graph);
				GL.assertEmpty();
			}
		} catch (Exception expected) {
			GL.assertEmpty();
			// expected
		}

		try {
			txnBegin(graph);
			try {
				graph.contains(triple("x R y"));
				fail("contains[triple] when closed");
			} catch (ClosedException c) { /* as required */
			} finally {
				txnRollback(graph);
				GL.assertEmpty();
			}
		} catch (Exception expected) {
			GL.assertEmpty();
			// expected
		}

		try {
			txnBegin(graph);
			try {
				graph.contains(Node.ANY, Node.ANY, Node.ANY);
				fail("contains[SPO] when closed");
			} catch (ClosedException c) { /* as required */
			} finally {
				txnRollback(graph);
				GL.assertEmpty();
			}
		} catch (Exception expected) {
			GL.assertEmpty();
			// expected
		}

		try {
			txnBegin(graph);
			try {
				graph.find(triple("x R y"));
				fail("find [triple] when closed");
			} catch (ClosedException c) { /* as required */
			} finally {
				txnRollback(graph);
				GL.assertEmpty();
			}
		} catch (Exception expected) {
			GL.assertEmpty();
			// expected
		}

		try {
			txnBegin(graph);
			try {
				graph.find(Node.ANY, Node.ANY, Node.ANY);
				fail("find[SPO] when closed");
			} catch (ClosedException c) { /* as required */
			} finally {
				txnRollback(graph);
				GL.assertEmpty();
			}
		} catch (Exception expected) {
			GL.assertEmpty();
			// expected
		}

		try {
			txnBegin(graph);
			try {
				graph.size();
				fail("size when closed (" + this.getClass() + ")");
			} catch (ClosedException c) { /* as required */
			} finally {
				txnRollback(graph);
				GL.assertEmpty();
			}
		} catch (Exception expected) {
			GL.assertEmpty();
			// expected
		}
	}

	@ContractTest
	public void testContains_Node_Node_Node() {
		Graph graph = graphWith(getProducer().newInstance(),
				"S P O; S2 P2 O2; S3 P3 O3");

		assertTrue(graph.contains(node("S"), node("P"), node("O")));
		assertFalse(graph.contains(node("S"), node("P"), node("O2")));
		assertFalse(graph.contains(node("S"), node("P2"), node("O")));
		assertFalse(graph.contains(node("S2"), node("P"), node("O")));
		assertTrue(graph.contains(Node.ANY, Node.ANY, Node.ANY));
		assertTrue(graph.contains(Node.ANY, Node.ANY, node("O")));
		assertTrue(graph.contains(Node.ANY, node("P"), Node.ANY));
		assertTrue(graph.contains(node("S"), Node.ANY, Node.ANY));
	}

	@ContractTest
	public void testContains_Node_Node_Node_RepeatedSubjectDoesNotConceal() {

		Graph g = graphWith(getProducer().newInstance(), "s P o; s Q r");
		Node s = node("s");
		Node P = node("P");
		Node o = node("o");
		Node Q = node("Q");
		Node r = node("r");
		Node any = node("??");
		assertTrue(g.contains(s, P, o));
		assertTrue(g.contains(s, Q, r));
		assertTrue(g.contains(any, P, o));
		assertTrue(g.contains(any, Q, r));
		assertTrue(g.contains(any, P, any));
		assertTrue(g.contains(any, Q, any));
	}

	@ContractTest
	public void testContains_Node_Node_Node_ByValue() {
		Node x = node("x");
		Node P = node("P");
		if (getProducer().newInstance().getCapabilities()
				.handlesLiteralTyping()) {
			Graph g1 = graphWith(getProducer().newInstance(),
					"x P '1'xsd:integer");
			assertTrue(
					String.format(
							"literal type equality failed, does %s really implement literal typing",
							g1.getClass()), g1.contains(x, P,
							node("'01'xsd:int")));
			//
			Graph g2 = graphWith(getProducer().newInstance(), "x P '1'xsd:int");
			assertTrue("Literal equality with '1'xsd:integer failed",
					g2.contains(x, P, node("'1'xsd:integer")));
			//
			Graph g3 = graphWith(getProducer().newInstance(),
					"x P '123'xsd:string");
			assertTrue("Literal equality with '123' failed",
					g3.contains(x, P, node("'123'")));
		}
	}

	@ContractTest
	public void testContains_Node_Node_Node_Concrete() {
		Node s = node("s");
		Node P = node("P");
		Node o = node("o");

		Node _x = node("_x");
		Node _R = node("_R");
		Node _y = node("_y");

		Node x = node("x");
		Node S = node("S");

		Graph g = graphWith(getProducer().newInstance(),
				"s P o; _x _R _y; x S 0");
		assertTrue("Graph should have contained s P o", g.contains(s, P, o));
		assertTrue("Graph should have contained _x _R _y",
				g.contains(_x, _R, _y));
		assertTrue("Graph should have contained x S 'O'",
				g.contains(x, S, node("0")));
		/* */
		assertFalse(g.contains(s, P, node("Oh")));
		assertFalse(g.contains(S, P, node("O")));
		assertFalse(g.contains(s, node("p"), o));
		assertFalse(g.contains(_x, node("_r"), _y));
		assertFalse(g.contains(x, S, node("1")));
	}

	@ContractTest
	public void testContains_Node_Node_Node_Fluid() {
		Node x = node("x");
		Node R = node("R");
		Node P = node("P");
		Node y = node("y");
		Node a = node("a");
		Node b = node("b");
		Graph g = graphWith(getProducer().newInstance(), "x R y; a P b");
		assertTrue(g.contains(Node.ANY, R, y));
		assertTrue(g.contains(x, Node.ANY, y));
		assertTrue(g.contains(x, R, Node.ANY));
		assertTrue(g.contains(Node.ANY, P, b));
		assertTrue(g.contains(a, Node.ANY, b));
		assertTrue(g.contains(a, P, Node.ANY));
		assertTrue(g.contains(Node.ANY, R, y));
		/* */
		assertFalse(g.contains(Node.ANY, R, b));
		assertFalse(g.contains(a, Node.ANY, y));
		assertFalse(g.contains(x, P, Node.ANY));
		assertFalse(g.contains(Node.ANY, R, x));
		assertFalse(g.contains(x, Node.ANY, R));
		assertFalse(g.contains(a, node("S"), Node.ANY));
	}

	@ContractTest
	public void testContains_Triple() {
		Graph graph = graphWith(getProducer().newInstance(),
				"S P O; S2 P2 O2; S3 P3 O3");

		assertTrue(graph.contains(triple("S P O")));
		assertFalse(graph.contains(triple("S P O2")));
		assertFalse(graph.contains(triple("S P2 O")));
		assertFalse(graph.contains(triple("S2 P O")));
		assertTrue(graph.contains(Triple.ANY));
		assertTrue(graph.contains(new Triple(Node.ANY, Node.ANY, node("O"))));
		assertTrue(graph.contains(new Triple(Node.ANY, node("P"), Node.ANY)));
		assertTrue(graph.contains(new Triple(node("S"), Node.ANY, Node.ANY)));

	}

	@ContractTest
	public void testContains_Triple_RepeatedSubjectDoesNotConceal() {

		Graph g = graphWith(getProducer().newInstance(), "s P o; s Q r");
		assertTrue(g.contains(triple("s P o")));
		assertTrue(g.contains(triple("s Q r")));
		assertTrue(g.contains(triple("?? P o")));
		assertTrue(g.contains(triple("?? Q r")));
		assertTrue(g.contains(triple("?? P ??")));
		assertTrue(g.contains(triple("?? Q ??")));
	}

	@ContractTest
	public void testContains_Triple_ByValue() {

		if (getProducer().newInstance().getCapabilities()
				.handlesLiteralTyping()) {
			Graph g1 = graphWith(getProducer().newInstance(),
					"x P '1'xsd:integer");
			assertTrue(
					String.format(
							"did not find x P '01'xsd:int, does %s really implement literal typing",
							g1.getClass()),
					g1.contains(triple("x P '01'xsd:int")));
			//
			Graph g2 = graphWith(getProducer().newInstance(), "x P '1'xsd:int");
			assertTrue("did not find x P '1'xsd:integer",
					g2.contains(triple("x P '1'xsd:integer")));
			//
			Graph g3 = graphWith(getProducer().newInstance(),
					"x P '123'xsd:string");
			assertTrue("did not find x P '123'xsd:string",
					g3.contains(triple("x P '123'")));
		}
	}

	@ContractTest
	public void testContains_Triple_Concrete() {
		Graph g = graphWith(getProducer().newInstance(),
				"s P o; _x _R _y; x S 0");
		assertTrue(g.contains(triple("s P o")));
		assertTrue(g.contains(triple("_x _R _y")));
		assertTrue(g.contains(triple("x S 0")));
		/* */
		assertFalse(g.contains(triple("s P Oh")));
		assertFalse(g.contains(triple("S P O")));
		assertFalse(g.contains(triple("s p o")));
		assertFalse(g.contains(triple("_x _r _y")));
		assertFalse(g.contains(triple("x S 1")));
	}

	@ContractTest
	public void testContains_Triple_Fluid() {
		Graph g = graphWith(getProducer().newInstance(), "x R y; a P b");
		assertTrue(g.contains(triple("?? R y")));
		assertTrue(g.contains(triple("x ?? y")));
		assertTrue(g.contains(triple("x R ??")));
		assertTrue(g.contains(triple("?? P b")));
		assertTrue(g.contains(triple("a ?? b")));
		assertTrue(g.contains(triple("a P ??")));
		assertTrue(g.contains(triple("?? R y")));
		/* */
		assertFalse(g.contains(triple("?? R b")));
		assertFalse(g.contains(triple("a ?? y")));
		assertFalse(g.contains(triple("x P ??")));
		assertFalse(g.contains(triple("?? R x")));
		assertFalse(g.contains(triple("x ?? R")));
		assertFalse(g.contains(triple("a S ??")));
	}

	/**
	 * Inference graphs can not be empty
	 */
	@ContractTest
	public void testDelete_Triple() {
		Graph graph = graphWith(getProducer().newInstance(),
				"S P O; S2 P2 O2; S3 P3 O3");
		Graph base = getProducer().newInstance();
		graph.getEventManager().register(GL);
		txnBegin(graph);
		graph.delete(triple("S P O"));
		txnCommit(graph);
		GL.assertContains("delete", graph, triple("S P O"));
		assertFalse("Graph should not contain <S P O>",
				graph.contains(triple("S P O")));
		assertNotEmpty(graph, base);
		assertTrue("Graph should contain <S2 P2 O2>",
				graph.contains(triple("S2 P2 O2")));
		assertTrue("Graph should contain <S3 P3 O3>",
				graph.contains(triple("S3 P3 O3")));

		// should not modify anything on wildcard delete
		GL.clear();
		try {
			txnBegin(graph);
			graph.delete(new Triple(node("S2"), node("P2"), Node.ANY));
			txnCommit(graph);
		} catch (DeleteDeniedException expected) {
			txnRollback(graph);
		}
		assertTrue("Graph should contain <S2 P2 O2>",
				graph.contains(triple("S2 P2 O2")));
		assertTrue("Graph should contain <S3 P3 O3>",
				graph.contains(triple("S3 P3 O3")));
		GL.assertHas("delete", graph, new Triple(node("S2"), node("P2"),
				Node.ANY));
	}

	@ContractTest
	public void testDelete_Triple_FromNothing() {
		Graph g = getProducer().newInstance();
		g.getEventManager().register(GL);
		txnBegin(g);
		g.delete(triple("quint rdf:subject S"));
		txnCommit(g);
		GL.assertContains("delete", g, triple("quint rdf:subject S"));
	}

	@ContractTest
	public void testDependsOn() {
		Graph g = getProducer().newInstance();
		Graph[] depGraphs = getProducer().getDependsOn(g);
		if (depGraphs != null) {
			for (Graph dg : depGraphs) {
				assertTrue(
						String.format("Graph %s should depend upon %s", g, dg),
						g.dependsOn(dg));
			}
		}
		depGraphs = getProducer().getNotDependsOn(g);
		if (depGraphs != null) {
			for (Graph dg : depGraphs) {
				assertFalse(String.format("Graph %s should not depend upon %s",
						g, dg), g.dependsOn(dg));
			}
		}
	}

	@ContractTest
	public void testFind_Node_Node_Node() {
		Graph graph = graphWith(getProducer().newInstance(),
				"S P O; S2 P2 O2; S3 P3 O3");
		List<Triple> s = graph.find(Node.ANY, Node.ANY, Node.ANY).toList();
		assertEquals(3, s.size());
		List<Triple> expected = Arrays.asList(new Triple[] { triple("S P O"),
				triple("S2 P2 O2"), triple("S3 P3 O3") });
		assertTrue("Missing some values",
				expected.containsAll(s) && s.containsAll(expected));

		s = graph.find(node("S"), Node.ANY, Node.ANY).toList();
		assertEquals(1, s.size());
		assertTrue("Missing some values", s.contains(triple("S P O")));

		s = graph.find(Node.ANY, node("P"), Node.ANY).toList();
		assertEquals(1, s.size());
		assertTrue("Missing some values", s.contains(triple("S P O")));

		s = graph.find(Node.ANY, Node.ANY, node("O")).toList();
		assertEquals(1, s.size());
		assertTrue("Missing some values", s.contains(triple("S P O")));

		s = graph.find(node("S2"), node("P2"), node("O2")).toList();
		assertEquals(1, s.size());
		assertTrue("Missing some values", s.contains(triple("S2 P2 O2")));

		s = graph.find(node("S2"), node("P3"), node("O2")).toList();
		assertEquals(0, s.size());

		s = graph.find(Node.ANY, node("P3"), node("O2")).toList();
		assertEquals(0, s.size());

		s = graph.find(node("S3"), Node.ANY, node("O2")).toList();
		assertEquals(0, s.size());

		s = graph.find(node("S3"), node("P2"), Node.ANY).toList();
		assertEquals(0, s.size());

	}

	@ContractTest
	public void testFind_Node_Node_Node_ByFluidTriple() {
		Node x = node("x");
		Node y = node("y");
		Node z = node("z");
		Graph g = graphWith(getProducer().newInstance(), "x y z ");
		Set<Triple> expect = tripleSet("x y z");
		assertEquals(expect, g.find(Node.ANY, y, z).toSet());
		assertEquals(expect, g.find(x, Node.ANY, z).toSet());
		assertEquals(expect, g.find(x, y, Node.ANY).toSet());
	}

	@ContractTest
	public void testFind_Node_Node_Node_ProgrammaticValues() {
		Graph g = getProducer().newInstance();
		if (g.getCapabilities().handlesLiteralTyping()) {
			Node ab = NodeFactory.createLiteral(LiteralLabelFactory
					.createTypedLiteral(new Byte((byte) 42)));
			Node as = NodeFactory.createLiteral(LiteralLabelFactory
					.createTypedLiteral(new Short((short) 42)));
			Node ai = NodeFactory.createLiteral(LiteralLabelFactory
					.createTypedLiteral(new Integer(42)));
			Node al = NodeFactory.createLiteral(LiteralLabelFactory
					.createTypedLiteral(new Long(42)));

			Node SB = NodeCreateUtils.create("SB");
			Node SS = NodeCreateUtils.create("SS");
			Node SI = NodeCreateUtils.create("SI");
			Node SL = NodeCreateUtils.create("SL");
			Node P = NodeCreateUtils.create("P");

			txnBegin(g);
			try {
				g.add(Triple.create(SB, P, ab));
				g.add(Triple.create(SS, P, as));
				g.add(Triple.create(SI, P, ai));
				g.add(Triple.create(SL, P, al));
			} catch (Exception e) {
				txnRollback(g);
				fail(e.getMessage());
			}
			txnCommit(g);
			assertEquals(
					String.format(
							"Should have found 4 elements, does %s really implement literal typing",
							g.getClass()),
					4,
					iteratorToSet(
							g.find(Node.ANY, P, NodeCreateUtils.create("42")))
							.size());
		}
	}

	@ContractTest
	public void testFind_Node_Node_Node_MatchLanguagedLiteralCaseInsensitive() {
		Graph m = graphWith(getProducer().newInstance(), "a p 'chat'en");
		if (m.getCapabilities().handlesLiteralTyping()) {
			Node chaten = node("'chat'en"), chatEN = node("'chat'EN");
			assertDiffer(chaten, chatEN);
			assertTrue(chaten.sameValueAs(chatEN));
			assertEquals(chaten.getIndexingValue(), chatEN.getIndexingValue());
			assertEquals(1, m.find(Node.ANY, Node.ANY, chaten).toList().size());
			assertEquals(1, m.find(Node.ANY, Node.ANY, chatEN).toList().size());
		}
	}

	@ContractTest
	public void testFind_Node_Node_Node_NoMatchAgainstUnlanguagesLiteral() {
		Graph m = graphWith(getProducer().newInstance(),
				"a p 'chat'en; a p 'chat'");
		if (m.getCapabilities().handlesLiteralTyping()) {
			Node chaten = node("'chat'en"), chatEN = node("'chat'EN");
			assertDiffer(chaten, chatEN);
			assertTrue(chaten.sameValueAs(chatEN));
			assertEquals(chaten.getIndexingValue(), chatEN.getIndexingValue());
			assertEquals(1, m.find(Node.ANY, Node.ANY, chaten).toList().size());
			assertEquals(1, m.find(Node.ANY, Node.ANY, chatEN).toList().size());
		}
	}

	@ContractTest
	public void testFind_Triple() {
		Graph graph = graphWith(getProducer().newInstance(),
				"S P O; S2 P2 O2; S3 P3 O3");
		List<Triple> s = graph.find(Triple.ANY).toList();
		assertEquals(3, s.size());
		List<Triple> expected = Arrays.asList(new Triple[] { triple("S P O"),
				triple("S2 P2 O2"), triple("S3 P3 O3") });
		assertTrue("Missing some values", expected.containsAll(s));

		s = graph.find(new Triple(node("S"), Node.ANY, Node.ANY)).toList();
		assertEquals(1, s.size());
		assertTrue("Missing some values", s.contains(triple("S P O")));

		s = graph.find(new Triple(Node.ANY, node("P"), Node.ANY)).toList();
		assertEquals(1, s.size());
		assertTrue("Missing some values", s.contains(triple("S P O")));

		s = graph.find(new Triple(Node.ANY, Node.ANY, node("O"))).toList();
		assertEquals(1, s.size());
		assertTrue("Missing some values", s.contains(triple("S P O")));

		s = graph.find(new Triple(node("S2"), node("P2"), node("O2"))).toList();
		assertEquals(1, s.size());
		assertTrue("Missing some values", s.contains(triple("S2 P2 O2")));

		s = graph.find(new Triple(node("S2"), node("P3"), node("O2"))).toList();
		assertEquals(0, s.size());

		s = graph.find(new Triple(Node.ANY, node("P3"), node("O2"))).toList();
		assertEquals(0, s.size());

		s = graph.find(new Triple(node("S3"), Node.ANY, node("O2"))).toList();
		assertEquals(0, s.size());

		s = graph.find(new Triple(node("S3"), node("P2"), Node.ANY)).toList();
		assertEquals(0, s.size());

	}

	@ContractTest
	public void testFind_Triple_ByFluidTriple() {
		Graph g = graphWith(getProducer().newInstance(), "x y z ");
		Set<Triple> expect = tripleSet("x y z");
		assertEquals(expect, g.find(triple("?? y z")).toSet());
		assertEquals(expect, g.find(triple("x ?? z")).toSet());
		assertEquals(expect, g.find(triple("x y ??")).toSet());
	}

	@ContractTest
	public void testFind_Triple_ProgrammaticValues() {
		Graph g = getProducer().newInstance();
		if (g.getCapabilities().handlesLiteralTyping()) {
			Node ab = NodeFactory.createLiteral(LiteralLabelFactory
					.createTypedLiteral(new Byte((byte) 42)));
			Node as = NodeFactory.createLiteral(LiteralLabelFactory
					.createTypedLiteral(new Short((short) 42)));
			Node ai = NodeFactory.createLiteral(LiteralLabelFactory
					.createTypedLiteral(new Integer(42)));
			Node al = NodeFactory.createLiteral(LiteralLabelFactory
					.createTypedLiteral(new Long(42)));

			Node SB = NodeCreateUtils.create("SB");
			Node SS = NodeCreateUtils.create("SS");
			Node SI = NodeCreateUtils.create("SI");
			Node SL = NodeCreateUtils.create("SL");
			Node P = NodeCreateUtils.create("P");

			txnBegin(g);
			try {
				g.add(Triple.create(SB, P, ab));
				g.add(Triple.create(SS, P, as));
				g.add(Triple.create(SI, P, ai));
				g.add(Triple.create(SL, P, al));
			} catch (Exception e) {
				txnRollback(g);
				fail(e.getMessage());
			}
			txnCommit(g);
			assertEquals(
					String.format(
							"Should have found 4 elements, does %s really implement literal typing",
							g.getClass()),
					4,
					iteratorToSet(
							g.find(new Triple(Node.ANY, P, NodeCreateUtils
									.create("42")))).size());
		}
	}

	@ContractTest
	public void testFind_Triple_MatchLanguagedLiteralCaseInsensitive() {
		Graph m = graphWith(getProducer().newInstance(), "a p 'chat'en");
		//if (m.getCapabilities().handlesLiteralTyping()) {
			Node chaten = node("'chat'en"), chatEN = node("'chat'EN");
			assertDiffer(chaten, chatEN);
			assertTrue(chaten.sameValueAs(chatEN));
			assertEquals(chaten.getIndexingValue(), chatEN.getIndexingValue());
			assertEquals(1, m.find(new Triple(Node.ANY, Node.ANY, chaten))
					.toList().size());
			assertEquals(1, m.find(new Triple(Node.ANY, Node.ANY, chatEN))
					.toList().size());
		//}
	}

	@ContractTest
	public void testFind_Triple_NoMatchAgainstUnlanguagesLiteral() {
		Graph m = graphWith(getProducer().newInstance(),
				"a p 'chat'en; a p 'chat'");
		//if (m.getCapabilities().handlesLiteralTyping()) {
			Node chaten = node("'chat'en"), chatEN = node("'chat'EN");
			assertDiffer(chaten, chatEN);
			assertTrue(chaten.sameValueAs(chatEN));
			assertEquals(chaten.getIndexingValue(), chatEN.getIndexingValue());
			assertEquals(1, m.find(new Triple(Node.ANY, Node.ANY, chaten))
					.toList().size());
			assertEquals(1, m.find(new Triple(Node.ANY, Node.ANY, chatEN))
					.toList().size());
		//}
	}

	@ContractTest
	public void testGetCapabilities() {
		Graph g = getProducer().newInstance();
		Capabilities c = g.getCapabilities();
		assertNotNull("Capabilities are not returned", c);
		try {
			c.sizeAccurate();
		} catch (Exception e) {
			fail("sizeAccurate() threw Exception: " + e.toString());
		}
		try {
			c.addAllowed();
		} catch (Exception e) {
			fail("addAllowed() threw Exception: " + e.toString());
		}
		try {
			c.addAllowed(true);
		} catch (Exception e) {
			fail("addAllowed( boolean ) threw Exception: " + e.toString());
		}
		try {
			c.deleteAllowed();
		} catch (Exception e) {
			fail("deleteAllowed() threw Exception: " + e.toString());
		}
		try {
			c.deleteAllowed(true);
		} catch (Exception e) {
			fail("deleteAllowed( boolean ) threw Exception: " + e.toString());
		}
		try {
			c.canBeEmpty();
		} catch (Exception e) {
			fail("canBeEmpty() threw Exception: " + e.toString());
		}
	}

	@ContractTest
	public void testGetEventManager() {
		assertNotNull("Must return an EventManager", getProducer()
				.newInstance().getEventManager());
	}

	@ContractTest
	public void testGetPrefixMapping() {
		Graph g = getProducer().newInstance();
		PrefixMapping pm = g.getPrefixMapping();
		assertNotNull("Must return prefix mapping", pm);
		assertSame("getPrefixMapping must always return the same object", pm,
				g.getPrefixMapping());

	
		pm.setNsPrefix("pfx1", "http://example.com/");
		pm.setNsPrefix("pfx2", "scheme:rope/string#");

		// assert same after adding to other mapl
		assertSame("getPrefixMapping must always return the same object", pm,
				g.getPrefixMapping());

	}

	@ContractTest
	public void testGetStatisticsHandler() {
		Graph g = getProducer().newInstance();
		GraphStatisticsHandler sh = g.getStatisticsHandler();
		if (sh != null) {
			assertSame(
					"getStatisticsHandler must always return the same object",
					sh, g.getStatisticsHandler());
		}
	}

	@ContractTest
	public void testGetTransactionHandler() {
		Graph g = getProducer().newInstance();
		assertNotNull("Must return a Transaction handler",
				g.getTransactionHandler());
	}

	@ContractTest
	public void testIsClosed() {
		Graph g = getProducer().newInstance();
		assertFalse("Graph created in closed state", g.isClosed());
		g.close();
		assertTrue("Graph does not report closed state after close called",
				g.isClosed());
	}

	@ContractTest
	public void testIsEmpty() {
		Graph g = getProducer().newInstance();
		if (!g.isEmpty()) {
			LOG.warn(String.format(
					"Graph type %s can not be empty (Empty test skipped)",
					g.getClass()));
		} else {
			graphAddTxn(g, "S P O");
			assertFalse("Graph reports empty after add", g.isEmpty());
			txnBegin(g);
			g.add(NodeCreateUtils.createTriple("Foo B C"));
			g.delete(NodeCreateUtils.createTriple("S P O"));
			txnCommit(g);
			assertFalse("Should not report empty", g.isEmpty());
			txnBegin(g);
			g.delete(NodeCreateUtils.createTriple("Foo B C"));
			txnCommit(g);
			assertTrue("Should report empty after all entries deleted",
					g.isEmpty());
		}
	}

	@ContractTest
	public void testIsIsomorphicWith_Graph() {
		Graph graph = getProducer().newInstance();
		Graph g2 = memGraph();
		assertTrue("Empty graphs should be isomorphic",
				graph.isIsomorphicWith(g2));

		graph = graphWith(getProducer().newInstance(),
				"S P O; S2 P2 O2; S3 P3 O3");
		g2 = graphWith("S3 P3 O3; S2 P2 O2; S P O");
		assertTrue("Should be isomorphic", graph.isIsomorphicWith(g2));
		txnBegin(graph);
		graph.add(triple("_1, P4 S4"));
		txnCommit(graph);

		txnBegin(g2);
		g2.add(triple("_2, P4 S4"));
		txnCommit(g2);
		assertTrue("Should be isomorphic after adding anonymous nodes",
				graph.isIsomorphicWith(g2));

		txnBegin(graph);
		graph.add(triple("_1, P3 S4"));
		txnCommit(graph);

		txnBegin(g2);
		g2.add(triple("_2, P4 S4"));
		txnCommit(g2);
		assertFalse("Should not be isomorphic", graph.isIsomorphicWith(g2));
	}

	private Graph copy(Graph g) {
		Graph result = getProducer().newInstance();
		txnBegin(result);
		GraphUtil.addInto(result, g);
		txnCommit(result);
		return result;
	}

	private Graph remove(Graph toUpdate, Graph toRemove) {
		txnBegin(toUpdate);
		GraphUtil.deleteFrom(toUpdate, toRemove);
		txnCommit(toUpdate);
		return toUpdate;
	}

	/**
	 * Test that remove(s, p, o) works, in the presence of inferencing graphs
	 * that mean emptyness isn't available. This is why we go round the houses
	 * and test that expected ~= initialContent + addedStuff - removed -
	 * initialContent.
	 */
	@ContractTest
	public void testRemove_Node_Node_Node() {
		for (int i = 0; i < cases.length; i += 1)
			for (int j = 0; j < 3; j += 1) {
				Graph content = getProducer().newInstance();

				Graph baseContent = copy(content);
				graphAddTxn(content, cases[i][0]);
				Triple remove = triple(cases[i][1]);
				Graph expected = graphWith(cases[i][2]);
				Triple[] removed = tripleArray(cases[i][3]);
				content.getEventManager().register(GL);
				GL.clear();
				txnBegin(content);
				content.remove(remove.getSubject(), remove.getPredicate(),
						remove.getObject());
				txnCommit(content);

				// check for optional delete notifications
				if (GL.contains("delete")) {
					// if it contains any it must contain all.
					for (Triple t : removed) {
						GL.assertContains("delete", content, t);
					}
				}
				GL.assertHasEnd(
						"someEvent",
						content,
						GraphEvents.remove(remove.getSubject(),
								remove.getPredicate(), remove.getObject()));

				content.getEventManager().unregister(GL);
				Graph finalContent = remove(copy(content), baseContent);
				assertIsomorphic(cases[i][1], expected, finalContent);
			}
	}

	@ContractTest
	public void testRemove_ByIterator() {
		testRemove("?? ?? ??", "?? ?? ??");
		testRemove("S ?? ??", "S ?? ??");
		testRemove("S ?? ??", "?? P ??");
		testRemove("S ?? ??", "?? ?? O");
		testRemove("?? P ??", "S ?? ??");
		testRemove("?? P ??", "?? P ??");
		testRemove("?? P ??", "?? ?? O");
		testRemove("?? ?? O", "S ?? ??");
		testRemove("?? ?? O", "?? P ??");
		testRemove("?? ?? O", "?? ?? O");
	}

	private void testRemove(String findRemove, String findCheck) {
		Graph g = graphWith(getProducer().newInstance(), "S P O");
		ExtendedIterator<Triple> it = g.find(NodeCreateUtils
				.createTriple(findRemove));
		try {
			it.next();
			it.remove();
			it.close();
			assertEquals("remove with " + findRemove + ":", 0, g.size());
			assertFalse(g.contains(NodeCreateUtils.createTriple(findCheck)));
		} catch (UnsupportedOperationException e) {
			it.close();
			assertFalse(
					"delete failed but capailities indicates it should work", g
							.getCapabilities().iteratorRemoveAllowed());
		}
	}

	/**
	 * This test case was generated by Ian and was caused by GraphMem not
	 * keeping up with changes to the find interface.
	 */
	@ContractTest
	public void testFindAndContains() {
		Graph g = getProducer().newInstance();
		Node r = NodeCreateUtils.create("r"), s = NodeCreateUtils.create("s"), p = NodeCreateUtils
				.create("P");
		txnBegin(g);
		try {
			g.add(Triple.create(r, p, s));
			txnCommit(g);
			assertTrue(g.contains(r, p, Node.ANY));
			assertEquals(1, g.find(r, p, Node.ANY).toList().size());
		} catch (Exception e) {
			txnRollback(g);
			fail(e.getMessage());
		}
	}

	/**
	 * Check that contains respects by-value semantics.
	 */

	@ContractTest
	public void testAGraph() {
		String title = this.getClass().getName();
		Graph g = getProducer().newInstance();
		int baseSize = g.size();
		graphAddTxn(g, "x R y; p S q; a T b");
		/* */
		assertContainsAll(title + ": simple graph", g, "x R y; p S q; a T b");
		assertEquals(title + ": size", baseSize + 3, g.size());

		graphAddTxn(g,
				"spindizzies lift cities; Diracs communicate instantaneously");
		assertEquals(title + ": size after adding", baseSize + 5, g.size());
		txnBegin(g);
		g.delete(triple("x R y"));
		g.delete(triple("a T b"));
		txnCommit(g);
		assertEquals(title + ": size after deleting", baseSize + 3, g.size());
		assertContainsAll(title + ": modified simple graph", g,
				"p S q; spindizzies lift cities; Diracs communicate instantaneously");
		assertOmitsAll(title + ": modified simple graph", g, "x R y; a T b");
		/* */
		ClosableIterator<Triple> it = g.find(Node.ANY, node("lift"), Node.ANY);
		assertTrue(title + ": finds some triple(s)", it.hasNext());
		assertEquals(title + ": finds a 'lift' triple",
				triple("spindizzies lift cities"), it.next());
		assertFalse(title + ": finds exactly one triple", it.hasNext());
		it.close();
	}

	// public void testStuff()
	// {
	// // testAGraph( "StoreMem", new GraphMem() );
	// // testAGraph( "StoreMemBySubject", new GraphMem() );
	// // String [] empty = new String [] {};
	// // Graph g = graphWith( "x R y; p S q; a T b" );
	// // /* */
	// // assertContainsAll( "simple graph", g, "x R y; p S q; a T b" );
	// // graphAdd( g,
	// "spindizzies lift cities; Diracs communicate instantaneously" );
	// // g.delete( triple( "x R y" ) );
	// // g.delete( triple( "a T b" ) );
	// // assertContainsAll( "modified simple graph", g,
	// "p S q; spindizzies lift cities; Diracs communicate instantaneously" );
	// // assertOmitsAll( "modified simple graph", g, "x R y; a T b" );
	// }

	// /**
	// Test that Graphs have transaction support methods, and that if they fail
	// on some g they fail because they do not support the operation.
	// */
	// @ContractTest
	// public void testHasTransactions()
	// {
	// Graph g = getProducer().newInstance();
	// TransactionHandler th = g.getTransactionHandler();
	// th.transactionsSupported();
	// try { th.begin(); } catch (UnsupportedOperationException x) {}
	// try { th.abort(); } catch (UnsupportedOperationException x) {}
	// try { th.begin(); th.commit(); } catch (UnsupportedOperationException x)
	// {}
	// /* */
	// Command cmd = new Command()
	// { @Override
	// public Object execute() { return null; } };
	// try { th.executeInTransaction( cmd ); }
	// catch (UnsupportedOperationException x) {}
	// }
	//
	// @ContractTest
	// public void testExecuteInTransactionCatchesThrowable()
	// {Graph g = getProducer().newInstance();
	// TransactionHandler th = g.getTransactionHandler();
	// if (th.transactionsSupported())
	// {
	// Command cmd = new Command()
	// { @Override
	// public Object execute() throws Error { throw new Error(); } };
	// try { th.executeInTransaction( cmd ); }
	// catch (JenaException x) {}
	// }
	// }

	@ContractTest
	public void testAddWithReificationPreamble() {
		Graph g = getProducer().newInstance();
		txnBegin(g);
		xSPO(g);
		txnCommit(g);
		assertFalse(g.isEmpty());
	}

	protected void xSPOyXYZ(Graph g) {
		xSPO(g);
		ReifierStd.reifyAs(g, NodeCreateUtils.create("y"),
				NodeCreateUtils.createTriple("X Y Z"));
	}

	protected void aABC(Graph g) {
		ReifierStd.reifyAs(g, NodeCreateUtils.create("a"),
				NodeCreateUtils.createTriple("Foo B C"));
	}

	protected void xSPO(Graph g) {
		ReifierStd.reifyAs(g, NodeCreateUtils.create("x"),
				NodeCreateUtils.createTriple("S P O"));
	}

	@ContractTest
	public void failingTestDoubleRemoveAll() {
		final Graph g = getProducer().newInstance();
		if (g.getCapabilities().iteratorRemoveAllowed()) {
			try {
				graphAddTxn(g, "c S d; e:ff GGG hhhh; _i J 27; Ell Em 'en'");
				Iterator<Triple> it = new TrackingTripleIterator(
						g.find(Triple.ANY)) {
					@Override
					public void remove() {
						super.remove(); // removes current
						g.delete(current); // no-op.
					}
				};
				while (it.hasNext()) {
					it.next();
					it.remove();
				}
				assertTrue(g.isEmpty());
			} catch (UnsupportedOperationException e) {
				fail("Error attempting to remove nodes " + e.getMessage());
			}
		}
	}

	/**
	 * Test cases for RemoveSPO(); each entry is a triple (add, remove, result).
	 * <ul>
	 * <li>add - the triples to add to the graph to start with
	 * <li>remove - the pattern to use in the removal
	 * <li>result - the triples that should remain in the graph
	 * </ul>
	 */
	protected static String[][] cases = { { "x R y", "x R y", "", "x R y" },
			{ "x R y; a P b", "x R y", "a P b", "x R y" },
			{ "x R y; a P b", "?? R y", "a P b", "x R y" },
			{ "x R y; a P b", "x R ??", "a P b", "x R y" },
			{ "x R y; a P b", "x ?? y", "a P b", "x R y" },
			{ "x R y; a P b", "?? ?? ??", "", "x R y; a P b" },
			{ "x R y; a P b; c P d", "?? P ??", "x R y", "a P b; c P d" },
			{ "x R y; a P b; x S y", "x ?? ??", "a P b", "x R y; x S y" }, };

	/**
	 * testIsomorphism from file data
	 * 
	 * @throws URISyntaxException
	 * @throws MalformedURLException 
	 */
	@ContractTest
	public void testIsomorphismFile() throws URISyntaxException, MalformedURLException {
		testIsomorphismXMLFile(1, true);
		testIsomorphismXMLFile(2, true);
		testIsomorphismXMLFile(3, true);
		testIsomorphismXMLFile(4, true);
		testIsomorphismXMLFile(5, false);
		testIsomorphismXMLFile(6, false);
		testIsomorphismNTripleFile(7, true);
		testIsomorphismNTripleFile(8, false);

	}

	private void testIsomorphismNTripleFile(int i, boolean result) {
		testIsomorphismFile(i, "N-TRIPLE", "nt", result);
	}

	private void testIsomorphismXMLFile(int i, boolean result) {
		testIsomorphismFile(i, "RDF/XML", "rdf", result);
	}

	private InputStream getInputStream(int n, int n2, String suffix) {
		String urlStr = String.format("regression/testModelEquals/%s-%s.%s", n,
				n2, suffix);
		return GraphContractTest.class.getClassLoader().getResourceAsStream(
				urlStr);
	}

	private void testIsomorphismFile(int n, String lang, String suffix, boolean result) {
		Graph g1 = getProducer().newInstance();
		Graph g2 = getProducer().newInstance();
		Model m1 = ModelFactory.createModelForGraph(g1);
		Model m2 = ModelFactory.createModelForGraph(g2);

		m1.read(getInputStream(n, 1, suffix), "http://www.example.org/", lang);

		m2.read(getInputStream(n, 2, suffix), "http://www.example.org/", lang);

		boolean rslt = g1.isIsomorphicWith(g2) == result;
		if (!rslt) {
			System.out.println("g1:");
			m1.write(System.out, "N-TRIPLE");
			System.out.println("g2:");
			m2.write(System.out, "N-TRIPLE");
		}
		assertTrue("Isomorphism test failed", rslt);
	}

	protected Graph getClosed() {
		Graph result = getProducer().newInstance();
		result.close();
		return result;
	}

	// @ContractTest
	// public void testTransactionCommit()
	// {
	// Graph g = getProducer().newInstance();
	// if (g.getTransactionHandler().transactionsSupported())
	// {
	// Graph initial = graphWithTxn( "initial hasValue 42; also hasURI hello" );
	// Graph extra = graphWithTxn( "extra hasValue 17; also hasURI world" );
	// //File foo = FileUtils.tempFileName( "fileGraph", ".nt" );
	//
	// //Graph g = new FileGraph( foo, true, true );
	//
	// GraphUtil.addInto( g, initial );
	// g.getTransactionHandler().begin();
	// GraphUtil.addInto( g, extra );
	// g.getTransactionHandler().commit();
	// Graph union = graphWithTxn( "" );
	// GraphUtil.addInto(union, initial );
	// GraphUtil.addInto(union, extra );
	// assertIsomorphic( union, g );
	// //Model inFile = ModelFactory.createDefaultModel();
	// //inFile.read( "file:///" + foo, "N-TRIPLES" );
	// //assertIsomorphic( union, inFile.getGraph() );
	// }
	// }
	//
	// @ContractTest
	// public void testTransactionAbort()
	// {
	// Graph g = getProducer().newInstance();
	// if (g.getTransactionHandler().transactionsSupported())
	// {
	// Graph initial = graphWithTxn( "initial hasValue 42; also hasURI hello" );
	// Graph extra = graphWithTxn( "extra hasValue 17; also hasURI world" );
	// File foo = FileUtils.tempFileName( "fileGraph", ".n3" );
	// //Graph g = new FileGraph( foo, true, true );
	// GraphUtil.addInto( g, initial );
	// g.getTransactionHandler().begin();
	// GraphUtil.addInto( g, extra );
	// g.getTransactionHandler().abort();
	// assertIsomorphic( initial, g );
	// }
	// }
	//
	// @ContractTest
	// public void testTransactionCommitThenAbort()
	// {
	// Graph g = getProducer().newInstance();
	// if (g.getTransactionHandler().transactionsSupported())
	// {
	// Graph initial = graphWithTxn( "Foo pings B; B pings C" );
	// Graph extra = graphWithTxn( "C pingedBy B; fileGraph rdf:type Graph" );
	// //Graph g = getProducer().newInstance();
	// //File foo = FileUtils.tempFileName( "fileGraph", ".nt" );
	// //Graph g = new FileGraph( foo, true, true );
	// g.getTransactionHandler().begin();
	// GraphUtil.addInto( g, initial );
	// g.getTransactionHandler().commit();
	// g.getTransactionHandler().begin();
	// GraphUtil.addInto( g, extra );
	// g.getTransactionHandler().abort();
	// assertIsomorphic( initial, g );
	// //Model inFile = ModelFactory.createDefaultModel();
	// // inFile.read( "file:///" + foo, "N-TRIPLES" );
	// //assertIsomorphic( initial, inFile.getGraph() );
	// }
	// }

	/**
	 * This test exposed that the update-existing-graph functionality was broken
	 * if the target graph already contained any statements with a subject S
	 * appearing as subject in the source graph - no further Spo statements were
	 * added.
	 */
	@ContractTest
	public void testPartialUpdate() {
		Graph source = graphWith(getProducer().newInstance(), "a R b; b S e");
		Graph dest = graphWith(getProducer().newInstance(), "b R d");
		GraphExtract e = new GraphExtract(TripleBoundary.stopNowhere);
		e.extractInto(dest, node("a"), source);
		assertIsomorphic(
				graphWith(getProducer().newInstance(), "a R b; b S e; b R d"),
				dest);
	}

	/**
	 * Ensure that triples removed by calling .remove() on the iterator returned
	 * by a find() will generate deletion notifications.
	 */
	@ContractTest
	public void testIterator_Remove() {
		Graph graph = graphWith(getProducer().newInstance(), "a R b; b S e");
		if (graph.getCapabilities().iteratorRemoveAllowed()) {
			try {
				graph.getEventManager().register(GL);
				txnBegin(graph);

				Triple toRemove = triple("a R b");
				ExtendedIterator<Triple> rtr = graph.find(toRemove);
				assertTrue("ensure a(t least) one triple", rtr.hasNext());
				rtr.next();
				rtr.remove();
				rtr.close();
				GL.assertHas("delete", graph, toRemove);
			} catch (UnsupportedOperationException e) {
				fail("Error attempting to remove nodes " + e.getMessage());
			}

		}
	}

	@ContractTest
	public void testTransactionHandler_Commit() {
		Graph g = getProducer().newInstance();
		if (g.getTransactionHandler().transactionsSupported()) {
			Graph initial = graphWith(getProducer().newInstance(),
					"initial hasValue 42; also hasURI hello");
			Graph extra = graphWith(getProducer().newInstance(),
					"extra hasValue 17; also hasURI world");

			GraphUtil.addInto(g, initial);
			g.getTransactionHandler().begin();
			GraphUtil.addInto(g, extra);
			g.getTransactionHandler().commit();
			Graph union = memGraph();
			GraphUtil.addInto(union, initial);
			GraphUtil.addInto(union, extra);
			assertIsomorphic(union, g);
			// Model inFiIProducer<TransactionHandler>le =
			// ModelFactory.createDefaultModel();
			// inFile.read( "file:///" + foo, "N-TRIPLES" );
			// assertIsomorphic( union, inFile.getGraph() );
		}
	}

	@ContractTest
	public void testTransactionHandler_Abort() {
		Graph g = getProducer().newInstance();
		if (g.getTransactionHandler().transactionsSupported()) {
			Graph initial = graphWith(getProducer().newInstance(),
					"initial hasValue 42; also hasURI hello");
			Graph extra = graphWith(getProducer().newInstance(),
					"extra hasValue 17; also hasURI world");
			GraphUtil.addInto(g, initial);
			g.getTransactionHandler().begin();
			GraphUtil.addInto(g, extra);
			g.getTransactionHandler().abort();
			assertIsomorphic(initial, g);
		}
	}

	@ContractTest
	public void testTransactionHandler_CommitThenAbort() {
		Graph g = getProducer().newInstance();
		if (g.getTransactionHandler().transactionsSupported()) {
			Graph initial = graphWith(getProducer().newInstance(),
					"Foo pings B; B pings C");
			Graph extra = graphWith(getProducer().newInstance(),
					"C pingedBy B; fileGraph rdf:type Graph");
			g.getTransactionHandler().begin();
			GraphUtil.addInto(g, initial);
			g.getTransactionHandler().commit();
			g.getTransactionHandler().begin();
			GraphUtil.addInto(g, extra);
			g.getTransactionHandler().abort();
			assertIsomorphic(initial, g);
			// Model inFile = ModelFactory.createDefaultModel();
			// inFile.read( "file:///" + foo, "N-TRIPLES" );
			// assertIsomorphic( initial, inFile.getGraph() );
		}
	}

	//
	// Test that literal typing works when supported
	//

	// used to find the object set from the returned set for literal testing

	private static final Function<Triple, Node> getObject = new Function<Triple, Node>() {
		@Override
		public Node apply(Triple t) {
			return t.getObject();
		}
	};

	private void testLiteralTypingBasedFind(final String data, final int size,
			final String search, final String results, boolean reqLitType) {

		Graph g = getProducer().newInstance();

		if (!reqLitType || g.getCapabilities().handlesLiteralTyping()) {
			graphWith(g, data);

			Node literal = NodeCreateUtils.create(search);
			//
			assertEquals("graph has wrong size", size, g.size());
			Set<Node> got = g.find(Node.ANY, Node.ANY, literal)
					.mapWith(getObject).toSet();
			assertEquals(nodeSet(results), got);
		}
	}

	@ContractTest
	public void testLiteralTypingBasedFind() {
		testLiteralTypingBasedFind("a P 'simple'", 1, "'simple'", "'simple'",
				false);
		testLiteralTypingBasedFind("a P 'simple'xsd:string", 1, "'simple'",
				"'simple'xsd:string", true);
		testLiteralTypingBasedFind("a P 'simple'", 1, "'simple'xsd:string",
				"'simple'", true);
		// ensure that adding identical strings one with type yields single result
		// and that querying with or without type works
		testLiteralTypingBasedFind("a P 'simple'xsd:string", 1,
				"'simple'xsd:string", "'simple'xsd:string", false);
		testLiteralTypingBasedFind("a P 'simple'; a P 'simple'xsd:string", 1,
				"'simple'", "'simple'xsd:string", true);
		testLiteralTypingBasedFind("a P 'simple'; a P 'simple'xsd:string", 1,
				"'simple'xsd:string", "'simple'", true);
		testLiteralTypingBasedFind("a P 'simple'; a P 'simple'xsd:string", 1,
				"'simple'", "'simple'", true);
		testLiteralTypingBasedFind("a P 'simple'; a P 'simple'xsd:string", 1,
				"'simple'xsd:string", "'simple'xsd:string", true);
		testLiteralTypingBasedFind("a P 1", 1, "1", "1", false);
		testLiteralTypingBasedFind("a P '1'xsd:float", 1, "'1'xsd:float",
				"'1'xsd:float", false);
		testLiteralTypingBasedFind("a P '1'xsd:double", 1, "'1'xsd:double",
				"'1'xsd:double", false);
		testLiteralTypingBasedFind("a P '1'xsd:float", 1, "'1'xsd:float",
				"'1'xsd:float", false);
		testLiteralTypingBasedFind("a P '1.1'xsd:float", 1, "'1'xsd:float", "",
				false);
		testLiteralTypingBasedFind("a P '1'xsd:double", 1, "'1'xsd:int", "",
				false);
		testLiteralTypingBasedFind("a P 'abc'rdf:XMLLiteral", 1, "'abc'", "",
				false);
		testLiteralTypingBasedFind("a P 'abc'", 1, "'abc'rdf:XMLLiteral", "",
				false);
		//
		// floats & doubles are not compatible
		//
		testLiteralTypingBasedFind("a P '1'xsd:float", 1, "'1'xsd:double", "",
				false);
		testLiteralTypingBasedFind("a P '1'xsd:double", 1, "'1'xsd:float", "",
				false);
		testLiteralTypingBasedFind("a P 1", 1, "'1'", "", false);
		testLiteralTypingBasedFind("a P 1", 1, "'1'xsd:integer",
				"'1'xsd:integer", false);
		testLiteralTypingBasedFind("a P 1", 1, "'1'", "", false);
		testLiteralTypingBasedFind("a P '1'xsd:short", 1, "'1'xsd:integer",
				"'1'xsd:short", true);
		testLiteralTypingBasedFind("a P '1'xsd:int", 1, "'1'xsd:integer",
				"'1'xsd:int", true);
	}

	@ContractTest
	public void testQuadRemove() {
		Graph g = getProducer().newInstance();
		assertEquals(0, g.size());
		Triple s = triple("x rdf:subject s");
		Triple p = triple("x rdf:predicate p");
		Triple o = triple("x rdf:object o");
		Triple t = triple("x rdf:type rdf:Statement");
		txnBegin(g);
		g.add(s);
		g.add(p);
		g.add(o);
		g.add(t);
		txnCommit(g);
		assertEquals(4, g.size());
		txnBegin(g);
		g.delete(s);
		g.delete(p);
		g.delete(o);
		g.delete(t);
		txnCommit(g);
		assertEquals(0, g.size());
	}

	@ContractTest
	public void testSizeAfterRemove() {
		Graph g = graphWith(getProducer().newInstance(), "x p y");
		if (g.getCapabilities().iteratorRemoveAllowed()) {
			try {
				ExtendedIterator<Triple> it = g.find(triple("x ?? ??"));
				it.removeNext();
				assertEquals(0, g.size());
			} catch (UnsupportedOperationException e) {
				fail("Error attempting to remove nodes " + e.getMessage());
			}
		}
	}

	@ContractTest
	public void testSingletonStatisticsWithSingleTriple() {

		Graph g = graphWith(getProducer().newInstance(), "a P b");
		GraphStatisticsHandler h = g.getStatisticsHandler();
		if (h != null) {
			assertEquals(1L, h.getStatistic(node("a"), Node.ANY, Node.ANY));
			assertEquals(0L, h.getStatistic(node("x"), Node.ANY, Node.ANY));
			//
			assertEquals(1L, h.getStatistic(Node.ANY, node("P"), Node.ANY));
			assertEquals(0L, h.getStatistic(Node.ANY, node("Q"), Node.ANY));
			//
			assertEquals(1L, h.getStatistic(Node.ANY, Node.ANY, node("b")));
			assertEquals(0L, h.getStatistic(Node.ANY, Node.ANY, node("y")));
		}
	}

	@ContractTest
	public void testSingletonStatisticsWithSeveralTriples() {

		Graph g = graphWith(getProducer().newInstance(),
				"a P b; a P c; a Q b; x S y");
		GraphStatisticsHandler h = g.getStatisticsHandler();
		if (h != null) {
			assertEquals(3L, h.getStatistic(node("a"), Node.ANY, Node.ANY));
			assertEquals(1L, h.getStatistic(node("x"), Node.ANY, Node.ANY));
			assertEquals(0L, h.getStatistic(node("y"), Node.ANY, Node.ANY));
			//
			assertEquals(2L, h.getStatistic(Node.ANY, node("P"), Node.ANY));
			assertEquals(1L, h.getStatistic(Node.ANY, node("Q"), Node.ANY));
			assertEquals(0L, h.getStatistic(Node.ANY, node("R"), Node.ANY));
			//
			assertEquals(2L, h.getStatistic(Node.ANY, Node.ANY, node("b")));
			assertEquals(1L, h.getStatistic(Node.ANY, Node.ANY, node("c")));
			assertEquals(0L, h.getStatistic(Node.ANY, Node.ANY, node("d")));
		}
	}

	@ContractTest
	public void testDoubletonStatisticsWithTriples() {

		Graph g = graphWith(getProducer().newInstance(),
				"a P b; a P c; a Q b; x S y");
		GraphStatisticsHandler h = g.getStatisticsHandler();
		if (h != null) {
			assertEquals(-1L, h.getStatistic(node("a"), node("P"), Node.ANY));
			assertEquals(-1L, h.getStatistic(Node.ANY, node("P"), node("b")));
			assertEquals(-1L, h.getStatistic(node("a"), Node.ANY, node("b")));
			//
			assertEquals(0L, h.getStatistic(node("no"), node("P"), Node.ANY));
		}
	}

	@ContractTest
	public void testStatisticsWithOnlyVariables() {
		testStatsWithAllVariables("");
		testStatsWithAllVariables("a P b");
		testStatsWithAllVariables("a P b; a P c");
		testStatsWithAllVariables("a P b; a P c; a Q b; x S y");
	}

	private void testStatsWithAllVariables(String triples) {
		Graph g = graphWith(getProducer().newInstance(), triples);
		GraphStatisticsHandler h = g.getStatisticsHandler();
		if (h != null) {
			assertEquals(g.size(), h.getStatistic(Node.ANY, Node.ANY, Node.ANY));
		}
	}

	@ContractTest
	public void testStatsWithConcreteTriple() {
		testStatsWithConcreteTriple(0, "x P y", "");
	}

	private void testStatsWithConcreteTriple(int expect, String triple,
			String graph) {
		Graph g = graphWith(getProducer().newInstance(), graph);
		GraphStatisticsHandler h = g.getStatisticsHandler();
		if (h != null) {
			Triple t = triple(triple);
			assertEquals(
					expect,
					h.getStatistic(t.getSubject(), t.getPredicate(),
							t.getObject()));
		}
	}

	@ContractTest
	public void testBrokenIndexes() {
		Graph g = graphWith(getProducer().newInstance(), "x R y; x S z");
		if (g.getCapabilities().iteratorRemoveAllowed()) {
			try {
				ExtendedIterator<Triple> it = g.find(Node.ANY, Node.ANY,
						Node.ANY);
				it.removeNext();
				it.removeNext();
				assertFalse(g.find(node("x"), Node.ANY, Node.ANY).hasNext());
				assertFalse(g.find(Node.ANY, node("R"), Node.ANY).hasNext());
				assertFalse(g.find(Node.ANY, Node.ANY, node("y")).hasNext());
			} catch (UnsupportedOperationException e) {
				fail("Error attempting to remove nodes " + e.getMessage());
			}
		}
	}

	@ContractTest
	public void testBrokenSubject() {
		Graph g = graphWith(getProducer().newInstance(), "x brokenSubject y");
		if (g.getCapabilities().iteratorRemoveAllowed()) {
			try {
				ExtendedIterator<Triple> it = g.find(node("x"), Node.ANY,
						Node.ANY);
				it.removeNext();
				assertFalse(g.find(Node.ANY, Node.ANY, Node.ANY).hasNext());
			} catch (UnsupportedOperationException e) {
				fail("Error attempting to remove nodes " + e.getMessage());
			}
		}
	}

	@ContractTest
	public void testBrokenPredicate() {
		Graph g = graphWith(getProducer().newInstance(), "x brokenPredicate y");
		if (g.getCapabilities().iteratorRemoveAllowed()) {
			try {
				ExtendedIterator<Triple> it = g.find(Node.ANY,
						node("brokenPredicate"), Node.ANY);
				it.removeNext();
				assertFalse(g.find(Node.ANY, Node.ANY, Node.ANY).hasNext());
			} catch (UnsupportedOperationException e) {
				fail("Error attempting to remove nodes " + e.getMessage());
			}
		}
	}

	@ContractTest
	public void testBrokenObject() {
		Graph g = graphWith(getProducer().newInstance(), "x brokenObject y");
		if (g.getCapabilities().iteratorRemoveAllowed()) {
			try {
				ExtendedIterator<Triple> it = g.find(Node.ANY, Node.ANY,
						node("y"));
				it.removeNext();
				assertFalse(g.find(Node.ANY, Node.ANY, Node.ANY).hasNext());

			} catch (UnsupportedOperationException e) {
				fail("Error attempting to remove nodes " + e.getMessage());
			}
		}
	}

}
