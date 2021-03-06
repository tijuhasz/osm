package org.neo4j.gis.osm.procedures;

import org.neo4j.gis.osm.model.OSMModel;
import org.neo4j.graphdb.*;
import org.neo4j.internal.kernel.api.exceptions.ProcedureException;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.procedure.*;

import java.util.*;
import java.util.stream.Stream;

public class OSMProcedures {
    @Context
    public Transaction tx;

    @Description("Given a point of interest node, and a collection of candidate ways to search, find or create a node on the way closest to the point of interest. " +
            "The returned node could be an existing node on the closest way, if such a node is close enough to the interpolation point. " +
            "If the interpolation point is not close to an existing node, one will be created at that point, and connected with ROUTE relationships to the adjacent existing nodes. " +
            "In both cases the closest node will be connected to the original point of interest with a ROUTE relationship, allowing subsequent shortest path routing on the graph.")
    @Procedure(value = "spatial.osm.routePointOfInterest", mode = Mode.WRITE)
    public Stream<PointRouteResult> findRouteToPointOfInterest(@Name("OSMNode") Node node, @Name("OSMWays") List<Node> ways) throws ProcedureException {
        try {
            OSMModel osm = new OSMModel();
            OSMModel.LocatedNode poi = osm.located(node);
            OSMModel.OSMWayDistance closestWay = ways.stream().map(osm::way).map(w -> w.closeTo(poi)).min(new OSMModel.ClosestWay()).orElse(null);
            if (closestWay == null) {
                throw new ProcedureException(Status.Procedure.ProcedureCallFailed, "Failed to find closest way from list of %d ways to node %s", ways.size(), node);
            }
            System.out.println("spatial.osm.routePointOfInterest(" + node + ") located closest way: " + closestWay);
            OSMModel.LocationMaker locationMaker = closestWay.getLocationMaker();
            Node connected = locationMaker.process(tx);
            System.out.println("spatial.osm.routePointOfInterest(" + node + ") created connected node: " + connected);
            return Stream.of(new PointRouteResult(connected));
        } catch (NullPointerException e) {
            e.printStackTrace();
            throw e;
        }
    }

    @Procedure(value = "spatial.osm.routeIntersection", mode = Mode.WRITE)
    public Stream<IntersectionRouteResult> findStreetRoute(@Name("OSMNode") Node node, @Name("deleteExistingRoutes") boolean deleteExistingRoutes, @Name("createNewRoutes") boolean createNewRoutes, @Name("addLabels") boolean addLabels) throws ProcedureException {
        try {
            OSMModel osm = new OSMModel();
            ArrayList<OSMModel.IntersectionRoutes> routesToSearch = new ArrayList<>();
            for (Relationship rel : node.getRelationships(Direction.INCOMING, OSMModel.NODE)) {
                routesToSearch.add(osm.intersectionRoutes(node, rel, rel.getStartNode(), addLabels));
            }
            ArrayList<IntersectionRouteResult> routesFound = new ArrayList<>();
            Collections.reverse(routesToSearch);
            for (OSMModel.IntersectionRoutes routes : routesToSearch) {
                if (routes.process(tx)) {
                    for(OSMModel.IntersectionRoute route:routes.routes) {
                        if (!deleteExistingRoutes && route.getExistingRoutes().size() > 0) {
                            System.out.println("Already have existing routes between " + route.fromNode + " and " + route.toNode);
                        } else {
                            if (deleteExistingRoutes) {
                                route.getExistingRoutes().forEach(Relationship::delete);
                            }
                            if (createNewRoutes) {
                                route.mergeRouteRelationship();
                            }
                            routesFound.add(new IntersectionRouteResult(route));
                        }
                    }
                } else {
                    System.out.println("Failed to find a routes for " + routes);
                }
            }
            System.out.println("Found " + routesFound.size() + " routes from " + node);
            return routesFound.stream();
        } catch (NullPointerException e) {
            e.printStackTrace();
            throw e;
        }
    }

    public static class IntersectionRouteResult {
        public Node fromNode;
        public Node wayNode;
        public Node toNode;
        public double distance;
        public long length;
        public long count;
        public Relationship fromRel;
        public Relationship toRel;

        public IntersectionRouteResult(OSMModel.IntersectionRoute route) {
            this.fromNode = route.fromNode;
            this.fromRel = route.fromRel;
            this.wayNode = route.wayNode;
            this.toNode = route.toNode;
            this.toRel = route.toRel;
            this.distance = route.distance;
            this.length = route.length;
            this.count = route.count;
        }
    }

    public class PointRouteResult {
        public Node node;

        public PointRouteResult(Node routeNode) {
            this.node = routeNode;
        }
    }
}
