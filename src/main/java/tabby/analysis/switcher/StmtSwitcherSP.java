package tabby.analysis.switcher;

import sootup.core.jimple.common.stmt.Stmt;
import tabby.analysis.container.ValueContainer;
import tabby.analysis.data.Context;

import java.util.Map;
import java.util.Set;

public class StmtSwitcherSP {
    private Context context;
    //    private ValueSwitcherSP valueSwitcher;
    private Map<String, Integer> triggerTimes;
    private Map<String, Set<String>> actions;

    public void accept(Context context) {
        this.context = context;
//        if (valueSwitcher == null) {
//            valueSwitcher = new ValueSwitcherSP(context);
//        } else {
//            valueSwitcher.accept(context);
//        }
    }

    public void setTriggerTimes(Map<String, Integer> triggerTimes) {
        this.triggerTimes = triggerTimes;
    }

    public void setActions(Map<String, Set<String>> actions) {
        this.actions = actions;
    }

    public void apply(Stmt stmt, ValueContainer container) {
        // 实现语句处理逻辑...
    }
}