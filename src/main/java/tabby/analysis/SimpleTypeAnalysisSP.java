package tabby.analysis;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import sootup.codepropertygraph.cfg.CfgCreator;
import sootup.codepropertygraph.propertygraph.PropertyGraph;
import sootup.codepropertygraph.propertygraph.edges.PropertyGraphEdge;
import sootup.codepropertygraph.propertygraph.nodes.StmtGraphNode;
import sootup.core.jimple.common.stmt.JInvokeStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.java.core.JavaSootMethod;
import tabby.analysis.container.ValueContainer;
import tabby.analysis.data.Context;
import tabby.analysis.model.CallEdgeBuilder;
import tabby.analysis.model.CallEdgeBuilderSP;
import tabby.analysis.switcher.StmtSwitcher;
import tabby.analysis.switcher.StmtSwitcherSP;
import tabby.common.bean.edge.Call;
import tabby.common.bean.ref.MethodReference;
import tabby.common.utils.SemanticUtils;
import tabby.config.GlobalConfiguration;

import java.util.*;

/**
 * SootUp版本的类型分析，不依赖传统Soot的UnitGraph
 */
@Setter
@Getter
@Slf4j
public class SimpleTypeAnalysisSP implements AutoCloseable {

    private Context context;
    private Map<String, Set<String>> actions = new HashMap<>();
    private Set<Call> callEdges = new HashSet<>();
    private CallEdgeBuilderSP callEdgeBuilder;
    private StmtSwitcherSP stmtSwitcher;
    private Map<String, Integer> triggerTimes = new HashMap<>();
    private Set<String> initialedCallEdge = new HashSet<>();
    private boolean isNormalExit = true;

    // 记录每个语句的分析状态
    private Map<Stmt, ValueContainer> stmtToFlow = new HashMap<>();

    public SimpleTypeAnalysisSP(Context context) {
        this.context = context;
        this.callEdgeBuilder = new CallEdgeBuilderSP(context.getDataContainer());
        this.stmtSwitcher = new StmtSwitcherSP();
        this.stmtSwitcher.setActions(actions);
    }

    /**
     * 执行分析
     */
    public void analyze(JavaSootMethod method) {
        MethodReference methodReference = context.getMethodReference();
        methodReference.setCallCounter(0);

        // 使用CfgCreator创建控制流图
        CfgCreator cfgCreator = new CfgCreator();
        PropertyGraph cfg = cfgCreator.createGraph(method);

        // 获取所有语句节点
        ArrayList<StmtGraphNode> stmtNodes = new ArrayList<>();
        for (var node : cfg.getNodes()) {
            if (node instanceof StmtGraphNode) {
                stmtNodes.add((StmtGraphNode) node);
            }
        }

        // 构建语句之间的控制流关系
        Map<Stmt, List<Stmt>> successorsMap = new HashMap<>();
        Map<Stmt, List<Stmt>> predecessorsMap = new HashMap<>();

        for (PropertyGraphEdge edge : cfg.getEdges()) {
            if (edge.getSource() instanceof StmtGraphNode && edge.getDestination() instanceof StmtGraphNode) {
                Stmt source = ((StmtGraphNode) edge.getSource()).getStmt();
                Stmt destination = ((StmtGraphNode) edge.getDestination()).getStmt();

                successorsMap.computeIfAbsent(source, k -> new ArrayList<>()).add(destination);
                predecessorsMap.computeIfAbsent(destination, k -> new ArrayList<>()).add(source);
            }
        }

        // 确定入口语句
        Stmt entryStmt = null;
        for (StmtGraphNode node : stmtNodes) {
            if (!predecessorsMap.containsKey(node.getStmt())) {
                entryStmt = node.getStmt();
                break;
            }
        }

        // 如果没有找到入口，使用第一个语句
        if (entryStmt == null && !stmtNodes.isEmpty()) {
            entryStmt = stmtNodes.get(0).getStmt();
        }

        // 执行工作表算法
        if (entryStmt != null) {
            Queue<Stmt> worklist = new LinkedList<>();
            worklist.add(entryStmt);

            // 设置入口语句的初始值
            ValueContainer entryValue = new ValueContainer(context.getDataContainer());
            stmtToFlow.put(entryStmt, entryValue);

            // 执行迭代数据流分析
            while (!worklist.isEmpty() && !context.isForceStop() && !context.isAnalyseTimeout()) {
                if (context.isTimeout()) {
                    context.setAnalyseTimeout(true);
                    isNormalExit = false;
                    break;
                }

                if (methodReference.isInitialed()) {
                    isNormalExit = false;
                    break;
                }

                Stmt stmt = worklist.remove();
                ValueContainer inValue = stmtToFlow.get(stmt);

                // 如果语句包含函数调用，收集调用边
                if (stmt instanceof JInvokeStmt && !initialedCallEdge.contains(stmt.toString())) {
                    JInvokeStmt invokeExpr = (JInvokeStmt) stmt;
                    callEdgeBuilder.build(stmt, invokeExpr, methodReference, callEdges, inValue);
                    initialedCallEdge.add(stmt.toString());
                }

                // 分析语句
                ValueContainer outValue = new ValueContainer(context.getDataContainer());
                outValue.copy(inValue);
                context.accept(outValue);

                // 应用语句分析
                stmtSwitcher.accept(context);
                stmtSwitcher.setTriggerTimes(triggerTimes);
                stmtSwitcher.apply(stmt, outValue);

                // 清理不必要的指向关系
                outValue.cleanUnnecessaryPts(outValue.getPointToSet());

                // 更新后继语句的值
                List<Stmt> successors = successorsMap.getOrDefault(stmt, Collections.emptyList());
                for (Stmt successor : successors) {
                    ValueContainer currentOut = stmtToFlow.get(successor);

                    if (currentOut != null) {
                        // 合并当前值和新值
                        ValueContainer mergedOut = new ValueContainer(context.getDataContainer());
                        mergedOut.copy(currentOut);
                        mergedOut.union(outValue);

                        if (!currentOut.equals(mergedOut)) {
                            stmtToFlow.put(successor, mergedOut);
                            worklist.add(successor);
                        }
                    } else {
                        ValueContainer newOut = new ValueContainer(context.getDataContainer());
                        newOut.copy(outValue);
                        stmtToFlow.put(successor, newOut);
                        worklist.add(successor);
                    }
                }
            }
        }

        // 完成分析
        doEnd();

    }

    /**
     * 完成分析
     */
    private void doEnd() {
        MethodReference ref = context.getMethodReference();
        context.setNormalExit(!context.isForceStop());
        ref.setContainSomeError(context.isForceStop());

        if (isNormalExit || !GlobalConfiguration.IS_NEED_ADD_TO_TIMEOUT_LIST) {
            context.getDataContainer().store(callEdges, false);
            context.getContainer().cleanUnnecessaryPts(actions);
            SemanticUtils.merge(actions, ref.getActions());
        }

        if (context.isAnalyseTimeout()) {
            if (context.isTopContext()) {
                context.getDataContainer().getAnalyseTimeoutMethodSigs().add(context.getMethodSignature());
            } else {
                Context preContext = context.getPreContext();
                preContext.setAnalyseTimeout(true);
            }
        }

        ref.setRunning(false);

        // 清理临时数据
        stmtToFlow.values().forEach(ValueContainer::clear);
        stmtToFlow.clear();
    }

    /**
     * 入口方法，用于处理SootUp的JavaSootMethod
     */
    public static boolean processMethodSP(JavaSootMethod method, Context context) {
        String signature = "";
        MethodReference methodReference = context.getMethodReference();
        if (methodReference.isBodyParseError()) return false;

        // 等待已运行的线程
        int maxSleepTimes = 5;
        while (methodReference.isRunning() && maxSleepTimes > 0) {
            int random = (int) (Math.random() * 20);
            try {
                Thread.sleep(random);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (methodReference.isActionInitialed()) {
                return true;
            }
            maxSleepTimes--;
        }

        try {
            signature = methodReference.getSignature();

            // 添加这个保护代码
            try {
                if (!method.hasBody()) {
                    log.debug("Method {} has no body", signature);
                    methodReference.setInitialed(true);
                    methodReference.setActionInitialed(true);
                    return false;
                }
            } catch (IncompatibleClassChangeError e) {
                log.warn("Type hierarchy error for {}, falling back to simple mode", signature);
                // 标记为已处理
                methodReference.setInitialed(true);
                methodReference.setActionInitialed(true);
                // 收集最基本的调用关系
                collectBasicCallEdges(method, methodReference, context);
                return true;
            }

            // 检查方法是否有主体
            if (!method.hasBody()) {
                log.debug("Method {} has no body", signature);
                methodReference.setInitialed(true);
                methodReference.setActionInitialed(true);
                return false;
            }

            // 获取方法体
            sootup.core.model.Body body = method.getBody();

            // 检查方法体大小
            if (body.getStmts().size() >= GlobalConfiguration.METHOD_MAX_BODY_COUNT) {
                log.debug("Method {} body is too big, ignore", signature);
                methodReference.setInitialed(true);
                methodReference.setActionInitialed(true);
                return false;
            }

            // 执行分析
            methodReference.setRunning(true);
            try (SimpleTypeAnalysisSP analysis = new SimpleTypeAnalysisSP(context)) {
                analysis.analyze(method);

                // 设置方法状态
                if (!context.isAnalyseTimeout() || !GlobalConfiguration.IS_NEED_ADD_TO_TIMEOUT_LIST) {
                    // 检查action交换和OOM状态
                    checkMethodStatus(methodReference);
                }

                return true;
            }

        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Body retrieve error: ")) {
                methodReference.setBodyParseError(true);
                log.error(msg);
            } else {
                log.error("Error processing method {}: {}", signature, msg);
                e.printStackTrace();
            }
        } finally {
            methodReference.setRunning(false);
        }

        return false;
    }

    /**
     * 检查方法状态（Action交换和OOM）
     */
    private static void checkMethodStatus(MethodReference methodReference) {
        // 检查action交换
        Map<String, Set<String>> actions = new HashMap<>(methodReference.getActions());
        for (String key : actions.keySet()) {
            Set<String> action = actions.get(key);
            if (action == null) continue;
            boolean flag = false;
            for (String act : action) {
                Set<String> action1 = actions.get(act);
                if (action1 != null && action1.contains(key)) {
                    methodReference.setActionContainsSwap(true);
                    flag = true;
                    break;
                } else {
                    Set<String> action2 = actions.get(act + "<s>");
                    if (action2 != null && action2.contains(key)) {
                        methodReference.setActionContainsSwap(true);
                        flag = true;
                        break;
                    }
                }
            }
            if (flag) break;
        }

        // 设置方法状态
        methodReference.setInitialed(true);
        methodReference.setActionInitialed(true);
    }

    private static void collectBasicCallEdges(JavaSootMethod method, MethodReference methodRef, Context context) {
        // 简单收集调用关系，不进行类型分析
        try {
            Set<Call> calls = new HashSet<>();
            method.getBody().getStmts().forEach(stmt -> {
//                if (stmt.containsInvokeExpr()) {
//                    // 创建基本调用边
//                    // ...
//                }
                System.out.println(stmt.toString());
            });
            if (!calls.isEmpty()) {
                context.getDataContainer().store(calls, false);
            }
        } catch (Exception e) {
            log.warn("Error collecting basic call edges: {}", e.getMessage());
        }
    }

    @Override
    public void close() throws Exception {
        context = null;
        stmtSwitcher = null;
        initialedCallEdge.clear();
        triggerTimes.clear();
        callEdges.clear();
        actions.clear();
    }
}
