package com.oracle.graal.pointsto.reports;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.reports.CallTreePrinter.InvokeNode;
import com.oracle.graal.pointsto.reports.CallTreePrinter.MethodNode;
import com.oracle.graal.pointsto.reports.CallTreePrinter.MethodNodeReference;
import com.oracle.graal.pointsto.reports.CallTreePrinter.Node;
import jdk.vm.ci.meta.ResolvedJavaMethod;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CallTreeCypher {

    private static final AtomicInteger virtualNodeId = new AtomicInteger(-1);

    public static void print(BigBang bigbang, String path, String reportName) {
        // Re-initialize method ids back to 0 to better diagnose disparities
        MethodNode.methodId = 0;

        CallTreePrinter printer = new CallTreePrinter(bigbang);
        printer.buildCallTree();

        // Set virtual node at next available method id
        virtualNodeId.set(MethodNode.methodId);

        printAll(printer.methodToNode, path, reportName);
    }

    private static void printAll(Map<AnalysisMethod, MethodNode> methodToNode, String path, String reportName) {
        Set<Integer> entryPointIds = new HashSet<>();
        Set<MethodNode> nonVirtualNodes = new HashSet<>();
        Map<List<String>, Integer> virtualNodes = new HashMap<>();

        Map<Integer, Set<BciEndEdge>> directEdges = new HashMap<>();
        Map<Integer, Set<BciEndEdge>> virtualEdges = new HashMap<>();
        Map<Integer, Set<Integer>> overridenByEdges = new HashMap<>();

        final Iterator<MethodNode> iterator = methodToNode.values().stream().filter(n -> n.isEntryPoint).iterator();
        while (iterator.hasNext()) {
            final MethodNode node = iterator.next();
            entryPointIds.add(node.id);
            walkNodes(node, directEdges, virtualEdges, overridenByEdges, virtualNodes, nonVirtualNodes);
        }

        final String vmFileName = ReportUtils.report("call tree for vm entry point", path + File.separatorChar + "reports", "csv_call_tree_vm_" + reportName, "csv",
                CallTreeCypher::printVMEntryPoint);

        final String methodsFileName = ReportUtils.report("call tree for methods", path + File.separatorChar + "reports", "csv_call_tree_methods_" + reportName, "csv",
                writer -> printMethodNodes(methodToNode.values(), writer));

        final String virtualMethodsFileName = ReportUtils.report("call tree for virtual methods", path + File.separatorChar + "reports", "csv_call_tree_virtual_methods_" + reportName, "csv",
                writer -> printVirtualNodes(virtualNodes, writer));

        final String entryPointsFileName = ReportUtils.report("call tree for entry points", path + File.separatorChar + "reports", "csv_call_tree_entry_points_" + reportName, "csv",
                writer -> printEntryPointIds(entryPointIds, writer));

        final String directEdgesFileName = ReportUtils.report("call tree for direct edges", path + File.separatorChar + "reports", "csv_call_tree_direct_edges_" + reportName, "csv",
                writer -> printBciEdges(directEdges, writer));

        final String overridenByEdgesFileName = ReportUtils.report("call tree for overriden by edges", path + File.separatorChar + "reports", "csv_call_tree_override_by_edges_" + reportName, "csv",
                writer -> printNonBciEdges(overridenByEdges, writer));

        final String virtualEdgesFileName = ReportUtils.report("call tree for virtual edges", path + File.separatorChar + "reports", "csv_call_tree_virtual_edges_" + reportName, "csv",
                writer -> printBciEdges(virtualEdges, writer));

        ReportUtils.report("call tree cypher", path + File.separatorChar + "reports", "cypher_call_tree_" + reportName, "cypher",
                writer -> printCypher(vmFileName, methodsFileName, virtualMethodsFileName, entryPointsFileName, directEdgesFileName, overridenByEdgesFileName, virtualEdgesFileName, writer));
    }

    private static void printCypher(String vmFileName, String methodsFileName, String virtualMethodsFileName, String entryPointsFileName, String directEdgesFileName, String overridenByEdgesFileName, String virtualEdgesFileName, PrintWriter writer) {
        writer.println("CREATE CONSTRAINT unique_vm_id ON (v:VM) ASSERT v.vmId IS UNIQUE;");
        writer.println("CREATE CONSTRAINT unique_method_id ON (m:Method) ASSERT m.methodId IS UNIQUE;");
        writer.println("");
        writer.println(String.format("LOAD CSV WITH HEADERS FROM 'file:///%s' AS row", vmFileName));
        writer.println("MERGE (v:VM {vmId: row.Id, name: row.Name})");
        writer.println("RETURN count(v);");
        writer.println("");
        writer.println(String.format("LOAD CSV WITH HEADERS FROM 'file:///%s' AS row", methodsFileName));
        writer.println("MERGE (m:Method {methodId: row.Id, name: row.Name, type: row.Type, parameters: row.Parameters, return: row.Return})");
        writer.println("RETURN count(m);");
        writer.println("");
        writer.println(String.format("LOAD CSV WITH HEADERS FROM 'file:///%s' AS row", virtualMethodsFileName));
        writer.println("MERGE (m:Method {methodId: row.Id, name: row.Name, type: row.Type, parameters: row.Parameters, return: row.Return})");
        writer.println("RETURN count(m);");
        writer.println("");
        writer.println(String.format("LOAD CSV WITH HEADERS FROM 'file:///%s' AS row", entryPointsFileName));
        writer.println("MATCH (m:Method {methodId: row.Id})");
        writer.println("MATCH (v:VM {vmId: '0'})");
        writer.println("MERGE (v)-[:ENTRY]->(m)");
        writer.println("RETURN count(*);");
        writer.println("");
        writer.println(String.format("LOAD CSV WITH HEADERS FROM 'file:///%s' AS row", directEdgesFileName));
        writer.println("MATCH (m1:Method {methodId: row.StartId})");
        writer.println("MATCH (m2:Method {methodId: row.EndId})");
        writer.println("MERGE (m1)-[:DIRECT {bci: row.BytecodeIndexes}]->(m2)");
        writer.println("RETURN count(*);");
        writer.println("");
        writer.println(String.format("LOAD CSV WITH HEADERS FROM 'file:///%s' AS row", overridenByEdgesFileName));
        writer.println("MATCH (m1:Method {methodId: row.StartId})");
        writer.println("MATCH (m2:Method {methodId: row.EndId})");
        writer.println("MERGE (m1)-[:OVERRIDEN_BY]->(m2)");
        writer.println("RETURN count(*);");
        writer.println("");
        writer.println(String.format("LOAD CSV WITH HEADERS FROM 'file:///%s' AS row", virtualEdgesFileName));
        writer.println("MATCH (m1:Method {methodId: row.StartId})");
        writer.println("MATCH (m2:Method {methodId: row.EndId})");
        writer.println("MERGE (m1)-[:VIRTUAL {bci: row.BytecodeIndexes}]->(m2)");
        writer.println("RETURN count(*);");
    }

    private static void printVMEntryPoint(PrintWriter writer) {
        writer.println(convertToCSV("Id", "Name"));
        writer.println(convertToCSV("0", "VM"));
    }

    private static void walkNodes(MethodNode methodNode, Map<Integer, Set<BciEndEdge>> directEdges, Map<Integer, Set<BciEndEdge>> virtualEdges, Map<Integer, Set<Integer>> overridenByEdges, Map<List<String>, Integer> virtualNodes, Set<MethodNode> nonVirtualNodes) {
        for (InvokeNode invoke : methodNode.invokes) {
            if (invoke.isDirectInvoke) {
                if (invoke.callees.size() > 0) {
                    Node calleeNode = invoke.callees.get(0);
                    addDirectEdge(methodNode.id, invoke, calleeNode, directEdges, nonVirtualNodes);
                    if (calleeNode instanceof MethodNode) {
                        walkNodes((MethodNode) calleeNode, directEdges, virtualEdges, overridenByEdges, virtualNodes, nonVirtualNodes);
                    }
                }
            } else {
                final int virtualNodeId = addVirtualNode(invoke, virtualNodes);
                addVirtualMethodEdge(methodNode.id, invoke, virtualNodeId, virtualEdges);
                for (Node calleeNode : invoke.callees) {
                    addOverridenByEdge(virtualNodeId, calleeNode, overridenByEdges, nonVirtualNodes);
                    if (calleeNode instanceof MethodNode) {
                        walkNodes((MethodNode) calleeNode, directEdges, virtualEdges, overridenByEdges, virtualNodes, nonVirtualNodes);
                    }
                }
            }
        }
    }

    private static void printMethodNodes(Collection<MethodNode> methods, PrintWriter writer) {
        writer.println(convertToCSV("Id", "Name", "Type", "Parameters", "Return"));
        methods.stream()
                .map(CallTreeCypher::methodNodeInfo)
                .map(CallTreeCypher::convertToCSV)
                .forEach(writer::println);
    }

    private static List<String> methodNodeInfo(MethodNode method) {
        return resolvedJavaMethodInfo(method.id, method.method);
    }

    private static int addVirtualNode(InvokeNode node, Map<List<String>, Integer> virtualNodes) {
        final List<String> virtualMethodInfo = virtualMethodInfo(node.targetMethod);
        return virtualNodes.computeIfAbsent(virtualMethodInfo, k -> CallTreeCypher.virtualNodeId.getAndIncrement());
    }

    private static void addVirtualMethodEdge(int startId, InvokeNode invoke, int endId, Map<Integer, Set<BciEndEdge>> edges) {
        Set<BciEndEdge> nodeEdges = edges.computeIfAbsent(startId, k -> new HashSet<>());
        nodeEdges.add(new BciEndEdge(endId, bytecodeIndexes(invoke)));
    }

    private static void addDirectEdge(int nodeId, InvokeNode invoke, Node calleeNode, Map<Integer, Set<BciEndEdge>> edges, Set<MethodNode> nodes) {
        Set<BciEndEdge> nodeEdges = edges.computeIfAbsent(nodeId, k -> new HashSet<>());
        MethodNode methodNode = calleeNode instanceof MethodNode
                ? (MethodNode) calleeNode
                : ((MethodNodeReference) calleeNode).methodNode;
        nodes.add(methodNode);
        nodeEdges.add(new BciEndEdge(methodNode.id, bytecodeIndexes(invoke)));
    }

    private static List<Integer> bytecodeIndexes(InvokeNode node) {
        return Stream.of(node.sourceReferences)
                .map(source -> source.bci)
                .collect(Collectors.toList());
    }

    private static void printVirtualNodes(Map<List<String>, Integer> virtualNodes, PrintWriter writer) {
        writer.println(convertToCSV("Id", "Name", "Type", "Parameters", "Return"));
        virtualNodes.entrySet().stream()
                .map(CallTreeCypher::virtualMethodAndIdInfo)
                .map(CallTreeCypher::convertToCSV)
                .forEach(writer::println);
    }

    private static List<String> virtualMethodAndIdInfo(Map.Entry<List<String>, Integer> entry) {
        final List<String> methodInfo = entry.getKey();
        final List<String> result = new ArrayList<>(methodInfo.size() + 1);
        result.add(String.valueOf(entry.getValue()));
        for (int i = 1; i < methodInfo.size(); i++) {
            result.add(i, methodInfo.get(i));
        }
        return result;
    }

    private static void printEntryPointIds(Set<Integer> entryPoints, PrintWriter writer) {
        writer.println(convertToCSV("Id"));
        entryPoints.forEach(writer::println);
    }

    private static void addOverridenByEdge(int nodeId, Node calleeNode, Map<Integer, Set<Integer>> edges, Set<MethodNode> nodes) {
        Set<Integer> nodeEdges = edges.computeIfAbsent(nodeId, k -> new HashSet<>());
        MethodNode methodNode = calleeNode instanceof MethodNode
                ? (MethodNode) calleeNode
                : ((MethodNodeReference) calleeNode).methodNode;
        nodes.add(methodNode);
        nodeEdges.add(methodNode.id);
    }

    private static void printBciEdges(Map<Integer, Set<BciEndEdge>> edges, PrintWriter writer) {
        final Set<BciEdge> idEdges = edges.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(endId -> new BciEdge(entry.getKey(), endId)))
                .collect(Collectors.toSet());

        writer.println(convertToCSV("StartId", "EndId", "BytecodeIndexes"));
        idEdges.stream()
                .map(edge -> convertToCSV(String.valueOf(edge.startId), String.valueOf(edge.endEdge.id), showBytecodeIndexes(edge.endEdge.bytecodeIndexes)))
                .forEach(writer::println);
    }

    private static String showBytecodeIndexes(List<Integer> bytecodeIndexes) {
        return bytecodeIndexes.stream()
                .map(String::valueOf)
                .collect(Collectors.joining("->"));
    }

    private static void printNonBciEdges(Map<Integer, Set<Integer>> edges, PrintWriter writer) {
        final Set<NonBciEdge> idEdges = edges.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(endId -> new NonBciEdge(entry.getKey(), endId)))
                .collect(Collectors.toSet());

        writer.println(convertToCSV("StartId", "EndId"));
        idEdges.stream()
                .map(edge -> convertToCSV(String.valueOf(edge.startId), String.valueOf(edge.endId)))
                .forEach(writer::println);
    }

    private static List<String> virtualMethodInfo(AnalysisMethod method) {
        return resolvedJavaMethodInfo(null, method);
    }

    private static List<String> resolvedJavaMethodInfo(Integer id, ResolvedJavaMethod method) {
        // TODO method parameter types are opaque, but could in the future be split out and link together
        //      e.g. each method could BELONG to a type, and a method could have PARAMETER relationships with N types
        //      see https://neo4j.com/developer/guide-import-csv/#_converting_data_values_with_load_csv for examples
        final String parameters =
                method.getSignature().getParameterCount(false) > 0
                        ? method.format("%P").replace(",", "")
                        : "empty";

        return Arrays.asList(
                id == null ? null : Integer.toString(id),
                method.getName(),
                method.getDeclaringClass().toJavaName(true),
                parameters,
                method.getSignature().getReturnType(null).toJavaName(true)
        );
    }

    private static String convertToCSV(String... data) {
        return String.join(",", data);
    }

    private static String convertToCSV(List<String> data) {
        return String.join(",", data);
    }

    private static final class NonBciEdge {

        final int startId;
        final int endId;

        private NonBciEdge(int startId, int endId) {
            this.startId = startId;
            this.endId = endId;
        }
    }

    private static final class BciEdge {
        final int startId;
        final BciEndEdge endEdge;

        private BciEdge(int startId, BciEndEdge endEdge) {
            this.startId = startId;
            this.endEdge = endEdge;
        }
    }

    private static final class BciEndEdge {
        final int id;
        final List<Integer> bytecodeIndexes;

        private BciEndEdge(int id, List<Integer> bytecodeIndexes) {
            this.id = id;
            this.bytecodeIndexes = bytecodeIndexes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BciEndEdge endEdge = (BciEndEdge) o;
            return id == endEdge.id &&
                    bytecodeIndexes.equals(endEdge.bytecodeIndexes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, bytecodeIndexes);
        }
    }
}
