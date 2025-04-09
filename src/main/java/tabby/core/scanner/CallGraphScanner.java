package tabby.core.scanner;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tabby.common.bean.ref.MethodReference;
import tabby.common.utils.TickTock;
import tabby.config.GlobalConfiguration;
import tabby.core.collector.CallGraphCollector;
import tabby.core.container.DataContainer;
import tabby.core.sootup.SootUpViewManager;
import tabby.dal.service.MethodRefService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * @author wh1t3P1g
 * @since 2020/11/17
 */
@Data
@Slf4j
@Component
public class CallGraphScanner {

    @Autowired
    private MethodRefService methodRefService;

    @Autowired
    private DataContainer dataContainer;

    @Autowired
    private CallGraphCollector collector;

    /**
     * 使用传统Soot运行调用图分析
     */
    public void run() {
        collect();
        save();
    }

    /**
     * 使用SootUp运行调用图分析
     */
    public void runSP() {
        // 确保SootUp视图已初始化
        if (!SootUpViewManager.getInstance().isInitialized()) {
            log.error("SootUp View not initialized. Please initialize it before running SootUp analysis");
            return;
        }

        collectSP();
        save();
    }

    /**
     * 使用传统Soot收集调用图信息
     */
    public void collect() {
        List<String> targets;
        if (GlobalConfiguration.IS_ON_DEMAND_DRIVE && GlobalConfiguration.IS_WEB_MODE) { // Web模式下 只分析 各类端点
            log.info("On-Demand-Drive mode, only analysis endpoints.");
            targets = new ArrayList<>(dataContainer.getOnDemandMethods());
        } else {
            targets = new ArrayList<>(dataContainer.getTargets());
        }
        log.info("Build call graph. START!");
        GlobalConfiguration.IS_NEED_ADD_TO_TIMEOUT_LIST = true;
        log.info("Set Method Timeout on {} min.", GlobalConfiguration.METHOD_TIMEOUT);
        doCollectWithNewAddedMethods(targets);
        int timeoutMethodSize = dataContainer.getAnalyseTimeoutMethodSigs().size();
        if (timeoutMethodSize > 0) {
            GlobalConfiguration.cleanStatus = true;
            GlobalConfiguration.METHOD_TIMEOUT = GlobalConfiguration.METHOD_TIMEOUT * 2; //
            GlobalConfiguration.METHOD_TIMEOUT_SECONDS = GlobalConfiguration.METHOD_TIMEOUT * 60L; //
            log.info("It has {} Methods timeout! Try to Analyse again!", timeoutMethodSize);
            log.info("Set Method Timeout on {} min.", GlobalConfiguration.METHOD_TIMEOUT);
            targets = new ArrayList<>(dataContainer.getAnalyseTimeoutMethodSigs());
            dataContainer.getAnalyseTimeoutMethodSigs().clear();
            doCollectWithNewAddedMethods(targets);
            GlobalConfiguration.cleanStatus = false;
        }
        timeoutMethodSize = dataContainer.getAnalyseTimeoutMethodSigs().size();
        if (timeoutMethodSize > 0) {
            log.info("It still has {} Methods timeout!", timeoutMethodSize);
        }
        GlobalConfiguration.tickTock = null;
        log.info("Build call graph. DONE!");
    }

    /**
     * 使用SootUp收集调用图信息
     */
    public void collectSP() {
        List<String> targets;
        if (GlobalConfiguration.IS_ON_DEMAND_DRIVE && GlobalConfiguration.IS_WEB_MODE) { // Web模式下 只分析 各类端点
            log.info("SootUp: On-Demand-Drive mode, only analysis endpoints.");
            targets = new ArrayList<>(dataContainer.getOnDemandMethods());
        } else {
            targets = new ArrayList<>(dataContainer.getTargets());
        }
        log.info("SootUp: Build call graph. START!");
        GlobalConfiguration.IS_NEED_ADD_TO_TIMEOUT_LIST = true;
        log.info("SootUp: Set Method Timeout on {} min.", GlobalConfiguration.METHOD_TIMEOUT);
        doCollectWithNewAddedMethodsSP(targets);
        int timeoutMethodSize = dataContainer.getAnalyseTimeoutMethodSigs().size();
        if (timeoutMethodSize > 0) {
            GlobalConfiguration.cleanStatus = true;
            GlobalConfiguration.METHOD_TIMEOUT = GlobalConfiguration.METHOD_TIMEOUT * 2;
            GlobalConfiguration.METHOD_TIMEOUT_SECONDS = GlobalConfiguration.METHOD_TIMEOUT * 60L;
            log.info("SootUp: It has {} Methods timeout! Try to Analyse again!", timeoutMethodSize);
            log.info("SootUp: Set Method Timeout on {} min.", GlobalConfiguration.METHOD_TIMEOUT);
            targets = new ArrayList<>(dataContainer.getAnalyseTimeoutMethodSigs());
            dataContainer.getAnalyseTimeoutMethodSigs().clear();
            doCollectWithNewAddedMethodsSP(targets);
            GlobalConfiguration.cleanStatus = false;
        }
        timeoutMethodSize = dataContainer.getAnalyseTimeoutMethodSigs().size();
        if (timeoutMethodSize > 0) {
            log.info("SootUp: It still has {} Methods timeout!", timeoutMethodSize);
        }
        GlobalConfiguration.tickTock = null;
        log.info("SootUp: Build call graph. DONE!");
    }

    /**
     * 传统Soot版本 - 处理包括新添加方法在内的所有方法
     */
    public void doCollectWithNewAddedMethods(List<String> targets) {
        boolean flag = true;
        while (!targets.isEmpty()) {
            doCollect(targets, flag);
            targets = new ArrayList<>(dataContainer.getNewAddedMethodSigs());
            dataContainer.setNewAddedMethodSigs(Collections.synchronizedSet(new HashSet<>()));

            if (targets.size() > 0) {
                log.info("Analyse {} newAddedMethods", targets.size());
                flag = false;
            }
        }
    }

    /**
     * SootUp版本 - 处理包括新添加方法在内的所有方法
     */
    public void doCollectWithNewAddedMethodsSP(List<String> targets) {
        boolean flag = true;
        while (!targets.isEmpty()) {
            doCollectSP(targets, flag);
            targets = new ArrayList<>(dataContainer.getNewAddedMethodSigs());
            dataContainer.setNewAddedMethodSigs(Collections.synchronizedSet(new HashSet<>()));

            if (targets.size() > 0) {
                log.info("SootUp: Analyse {} newAddedMethods", targets.size());
                flag = false;
            }
        }
    }

    /**
     * 传统Soot版本 - 收集方法信息
     */
    public void doCollect(List<String> targets, boolean show) {
        int total = targets.size();
        TickTock tt = new TickTock(total, show);
        Collections.shuffle(targets);
        if (GlobalConfiguration.cleanStatus) {
            for (String signature : targets) {
                MethodReference ref = dataContainer.getMethodRefBySignature(signature, false);
                if (ref != null) {
                    ref.cleanStatus();
                }
            }
        }
        for (String signature : targets) {
            MethodReference ref = dataContainer.getMethodRefBySignature(signature, false);
            if (ref != null) {
                collector.collect(ref, dataContainer, tt);
            } else {
                tt.countDown();
            }
        }
        tt.await();
    }

    /**
     * SootUp版本 - 收集方法信息
     */
    public void doCollectSP(List<String> targets, boolean show) {
        int total = targets.size();
        TickTock tt = new TickTock(total, show);
        Collections.shuffle(targets);

        // 清除状态（如果需要）
        if (GlobalConfiguration.cleanStatus) {
            for (String signature : targets) {
                MethodReference ref = dataContainer.getMethodRefBySignature(signature, false);
                if (ref != null) {
                    ref.cleanStatus();
                }
            }
        }

        // 处理每个方法
        for (String signature : targets) {
            MethodReference ref = dataContainer.getMethodRefBySignature(signature, false);
            if (ref != null) {
                // 使用SootUp版本的收集器
                collector.collectSP(ref, dataContainer, tt);
            } else {
                tt.countDown();
            }
        }

        tt.await();
    }

    /**
     * 保存收集到的数据到数据库/文件
     */
    public void save() {
        if (GlobalConfiguration.GLOBAL_FORCE_STOP) return;
        log.info("Try to save classes.");
        dataContainer.save("class");
        log.info("Try to save methods.");
        dataContainer.save("method");
        log.info("Try to save call edges.");
        dataContainer.save("call");
    }
}