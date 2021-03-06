package org.neo4j.gis.osm.model;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.*;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;
import org.neo4j.values.storable.PointValue;

import java.util.ArrayList;
import java.util.HashSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class OSMModelIntegrationTest {

    private DatabaseManagementService databases;
    private GraphDatabaseService db;

    @Before
    public void setup() {
        this.databases = new TestDatabaseManagementServiceBuilder().impermanent().build();
        this.db = databases.database("neo4j");
        try (Transaction tx = db.beginTx()) {
            TestOSMModel osm = new TestOSMModel(tx);
            osm.buildSquare(10);
            osm.buildMultiChain("ChainTopRight", 10, 10, 10, 5, 1, 1);
            osm.buildMultiChain("ChainBottomRight", 10, 0, 10, 5, 1, -1);
            osm.buildMultiChain("ChainTopLeft", 0, 10, 10, 5, -1, 1);
            osm.buildMultiChain("ChainBottomLeft", 0, 0, 10, 5, -1, -1);
            osm.addIntersectionLabels();
            tx.commit();
        }
    }

    @After
    public void shutdown() {
        this.databases.shutdown();
    }

    @Test
    public void shouldFindClosest() {
        try (Transaction tx = db.beginTx()) {
            TestOSMModel osm = new TestOSMModel(tx);
            assertFindWay(osm, "Left", 2, 112000, 1, 2);
            assertFindWay(osm, "Top", 2, 112000, 2, 9);
            assertFindWay(osm, "Right", 2, 112000, 9, 2);
            assertFindWay(osm, "Bottom", 8, 112000, 8, 1);
            tx.commit();
        }
    }

    @Test
    public void shouldFindClosestFurther() {
        try (Transaction tx = db.beginTx()) {
            TestOSMModel osm = new TestOSMModel(tx);
            assertFindWay(osm, "Bottom", 5, 112000, 5, 1);
            assertFindWay(osm, "Bottom", 5, 223000, 5, 2);
            assertFindWay(osm, "Bottom", 5, 334000, 5, 3);
            assertFindWay(osm, "Bottom", 5, 446000, 5, 4);
            tx.commit();
        }
    }

    @Test
    public void shouldFindClosestCloser() {
        try (Transaction tx = db.beginTx()) {
            TestOSMModel osm = new TestOSMModel(tx);
            assertFindWay(osm, "Bottom", 5, 112000, 5, 1);
            assertFindWay(osm, "Bottom", 5, 56000, 5, 0.5);
            assertFindWay(osm, "Bottom", 5, 11200, 5, 0.1);
            tx.commit();
        }
    }

    @Test
    public void shouldInterpolateNewNode() {
        assertFindWayAndInterpolatedPoint("Bottom", new int[]{4, 5}, 112000, 4.5, 1);
        assertFindWayAndInterpolatedPoint("Bottom", new int[]{4, 5}, 56000, 4.5, 0.5);
        assertFindWayAndInterpolatedPoint("Bottom", new int[]{4, 5}, 11200, 4.5, 0.1);
        assertFindWayAndInterpolatedPoint("Bottom", new int[]{4, 5}, 1120, 4.5, 0.01);
        assertFindWayAndInterpolatedPoint("Bottom", new int[]{4, 5}, 112, 4.5, 0.001);
        assertFindWayAndInterpolatedPoint("Bottom", new int[]{4, 5}, 112, 4.5, -0.001);
        assertFindWayAndInterpolatedPoint("Bottom", new int[]{4, 5}, 1120, 4.5, -0.01);
        assertFindWayAndInterpolatedPoint("Bottom", new int[]{4, 5}, 11200, 4.5, -0.1);
        assertFindWayAndInterpolatedPoint("Bottom", new int[]{4, 5}, 56000, 4.5, -0.5);
        assertFindWayAndInterpolatedPoint("Bottom", new int[]{4, 5}, 112000, 4.5, -1);
    }

    @Test
    public void shouldInterpolateNewNode2() {
        assertFindWayAndInterpolatedPoint("Right", new int[]{7, 8}, 221000, 8, 7.5);
        assertFindWayAndInterpolatedPoint("Right", new int[]{7, 8}, 112000, 9, 7.5);
        assertFindWayAndInterpolatedPoint("Right", new int[]{7, 8}, 10, 10, 7.5);
        assertFindWayAndInterpolatedPoint("Right", new int[]{7, 8}, 112000, 11, 7.5);
        assertFindWayAndInterpolatedPoint("Right", new int[]{7, 8}, 221000, 12, 7.5);
    }

    @Test
    public void shouldFollowConnectedChain() {
        try (Transaction tx = db.beginTx()) {
            TestOSMModel osm = new TestOSMModel(tx);
            OSMModel.OSMWay chain0 = osm.getWay("ChainTopRight-0");
            Node startNode = chain0.wayNodes.get(0);
            Relationship rel = startNode.getSingleRelationship(OSMModel.NODE, Direction.OUTGOING);
            for (int i = 0; i < 5; i++) {
                OSMModel.IntersectionRoutes.PathSegmentTree pathSegment = new OSMModel.IntersectionRoutes.PathSegmentTree(startNode, Direction.OUTGOING);
                assertThat("Should be able to follow path segment " + i, pathSegment.process(tx), equalTo(true));
                assertThat("Should have found a path segment " + i + " of length 10", pathSegment.length, equalTo(10));
                ArrayList<Relationship> nextRels = pathSegment.nextWayRels();
                if (i < 4) {
                    assertThat("Should only have 1 possible next relationship to follow for path segment " + i, nextRels.size(), equalTo(1));
                    startNode = nextRels.get(0).getStartNode();
                } else {
                    assertThat("Should only have 2 possible next relationships to follow for path segment " + i, nextRels.size(), equalTo(2));
                }
            }
            tx.commit();
        }
    }

    @Test
    public void shouldFindIntersections() {
        try (Transaction tx = db.beginTx()) {
            TestOSMModel osm = new TestOSMModel(tx);
            OSMModel.OSMWay chain0 = osm.getWay("ChainTopRight-0");
            OSMModel.OSMWay chain5u = osm.getWay("ChainTopRight-5u");
            OSMModel.OSMWay chain5d = osm.getWay("ChainTopRight-5d");
            Node startNode = chain0.wayNodes.get(0);
            Relationship rel = startNode.getSingleRelationship(OSMModel.NODE, Direction.OUTGOING);
            OSMModel.IntersectionRoutes routes = osm.intersectionRoutes(rel.getEndNode(), rel, startNode, true);
            assertThat("Should succeed in finding an intersection", routes.process(tx), equalTo(true));
            assertThat("Should find one route from", routes.routes.size(), equalTo(1));
            OSMModel.IntersectionRoute route = routes.routes.get(0);
            assertThat("Should find intersection to first node of chain-5u", route.toNode, equalTo(chain5u.nodes.get(0).node()));
            assertThat("Should find intersection to first node of chain-5d", route.toNode, equalTo(chain5d.nodes.get(0).node()));
            assertThat("Last chain relationship should point to first node of next chain", route.toRel.getEndNode(), equalTo(chain5u.nodes.get(0).node()));
            tx.commit();
        }
    }

    private void assertFindWay(TestOSMModel osm, String name, int expectedNode, double maxDist, double... coords) {
        OSMModel.OSMWay expectedWay = osm.getWay(name);
        OSMModel.LocatedNode poi = osm.makeNode(coords);
        OSMModel.OSMWayDistance closest = osm.ways.stream().map(w->w.closeTo(poi)).min(new OSMModel.ClosestWay()).orElse(null);
        assertNotNull("Expected to find a closest way, but was null", closest);
        assertThat("Found way with wrong name", closest.way.getName(), equalTo(name));
        assertThat("Found wrong way", closest.way, equalTo(expectedWay));
        OSMModel.OSMWayDistance.DistanceResult distanceResult = closest.closest;
        assertThat("Distance to found node is too long", distanceResult.nodeDistance, lessThan(maxDist));
        assertThat("Not the closest node expected from index " + expectedNode, distanceResult.closestNodeIndex, equalTo(expectedNode));
        OSMModel.LocationMaker location = distanceResult.getLocationMaker();
        assertThat(location, instanceOf(OSMModel.LocationExists.class));
        OSMModel.LocatedNode expectedClosestNode = expectedWay.nodes.get(expectedNode);
        assertThat("Should find the expected node", ((OSMModel.LocationExists) location).node, equalTo(expectedClosestNode.node()));
    }

    private void assertFindWayAndInterpolatedPoint(String name, int[] expectedPair, double maxDist, double... coords) {
        // TODO consider reverting to multiple transactions as in 3.5 version
        try (Transaction tx = db.beginTx()) {
            TestOSMModel osm = new TestOSMModel(tx);
            OSMModel.OSMWay expectedWay = osm.getWay(name);
            OSMModel.LocatedNode poi = osm.makeNode(coords);
            OSMModel.OSMWayDistance closest = osm.ways.stream().map(w -> w.closeTo(poi)).min(new OSMModel.ClosestWay()).orElse(null);
            assertNotNull("Expected to find a closest way, but was null", closest);
            assertThat("Found way with wrong name", closest.way.getName(), equalTo(name));
            assertThat("Found wrong way", closest.way, equalTo(expectedWay));
            OSMModel.OSMWayDistance.DistanceResult distanceResult = closest.closest;
            OSMModel.LocatedNode left = expectedWay.nodes.get(expectedPair[0]);
            OSMModel.LocatedNode right = expectedWay.nodes.get(expectedPair[1]);
            OSMModel.LocationMaker location = distanceResult.getLocationMaker();
            OSMModel.Triangle triangle;
            Node node;
            node = location.process(tx);
            PointValue point = (PointValue) node.getProperty("location");
            triangle = new OSMModel.Triangle(point, left.point(), right.point());
            Node leftNode = null;
            Node rightNode = null;
            if (location instanceof OSMModel.LocationInterpolated) {
                OSMModel.LocationInterpolated interpolated = (OSMModel.LocationInterpolated) location;
                leftNode = interpolated.left.node();
                rightNode = interpolated.right.node();
                Node connected = poi.node().getSingleRelationship(OSMModel.ROUTE, Direction.OUTGOING).getEndNode();
                assertThat("Should be connected to new node", connected, equalTo(node));
            } else if (location instanceof OSMModel.LocationIsPoint) {
                OSMModel.LocationIsPoint interpolated = (OSMModel.LocationIsPoint) location;
                leftNode = interpolated.left;
                rightNode = interpolated.right;
            } else {
                fail("Unknown location type: " + location.getClass().getSimpleName());
            }
            assertThat("Projected point should be on the line between the two original points", triangle.apexAngle(), closeTo(180.0, 5.0));
            assertThat("Should find the expected left node", leftNode, equalTo(left.node()));
            assertThat("Should find the expected right node", rightNode, equalTo(right.node()));
            double distance = distanceResult.calculator.distance(triangle.apex, poi.point());
            assertThat("Distance to interpolated node is too long", distance, lessThan(maxDist));
            assertConnectedNodes(node, leftNode, rightNode);
            tx.commit();
        }
    }

    private void assertConnectedNodes(Node node, Node left, Node right) {
        HashSet<Node> nodes = new HashSet<>();
        for (Relationship rel : node.getRelationships(Direction.OUTGOING, OSMModel.ROUTE)) {
            assertThat("Route relationship should have distance property", rel.hasProperty("distance"), equalTo(true));
            nodes.add(rel.getEndNode());
        }
        assertThat("Should be connected to two nodes", nodes.size(), equalTo(2));
        assertThat("Should contain left and right node", nodes, contains(left, right));
    }
}
