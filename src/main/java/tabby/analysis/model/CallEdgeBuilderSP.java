package tabby.analysis.model;

import sootup.core.jimple.common.stmt.JInvokeStmt;
import sootup.core.jimple.common.stmt.Stmt;
import tabby.analysis.container.ValueContainer;
import tabby.common.bean.edge.Call;
import tabby.common.bean.ref.MethodReference;
import tabby.core.container.DataContainer;

import java.util.Set;

public class CallEdgeBuilderSP {
    private DataContainer dataContainer;

    public CallEdgeBuilderSP(DataContainer dataContainer) {
        this.dataContainer = dataContainer;
    }

    public void build(Stmt stmt, JInvokeStmt invokeExpr, MethodReference methodRef, Set<Call> callEdges, ValueContainer container) {
        // 实现调用边构建逻辑...
    }
}