package tabby.core.scanner;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import soot.ModulePathSourceLocator;
import soot.Scene;
import soot.SootClass;
import sootup.java.core.JavaSootClass;
import sootup.java.core.views.JavaView;
import tabby.common.bean.ref.ClassReference;
import tabby.common.utils.SemanticUtils;
import tabby.config.GlobalConfiguration;
import tabby.core.collector.ClassInfoCollector;
import tabby.core.container.DataContainer;
import tabby.core.sootup.SootUpViewManager;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static tabby.config.GlobalConfiguration.IS_BUILD_WIH_CACHE_ENABLE;
import static tabby.config.GlobalConfiguration.rulesContainer;

/**
 * 处理jdk相关类的信息抽取
 *
 * @author wh1t3P1g
 * @since 2020/10/21
 */
@Data
@Slf4j
@Component
public class ClassInfoScanner {

    @Autowired
    private DataContainer dataContainer;

    @Autowired
    private ClassInfoCollector collector;

    public void run(List<String> paths) {
        // 多线程提取基础信息
        Map<String, CompletableFuture<ClassReference>> classes = loadAndExtract(paths);
        transform(classes.values()); // 等待收集结束，并保存classRef
        List<String> runtimeClasses = new ArrayList<>(classes.keySet());
        classes.clear();
        // 单线程提取关联信息
        buildClassEdges(runtimeClasses);
        save();
    }

    public void runSP(List<String> paths) {
        // 多线程提取基础信息
        Map<String, CompletableFuture<ClassReference>> classes = loadAndExtractSP(paths);
        transform(classes.values()); // 等待收集结束，并保存classRef
        List<String> runtimeClasses = new ArrayList<>(classes.keySet());
        classes.clear();
        // 单线程提取关联信息
        buildClassEdgesSP(runtimeClasses);
        save();
    }

    public Map<String, CompletableFuture<ClassReference>> loadAndExtractSP(List<String> targets) {
        Map<String, CompletableFuture<ClassReference>> results = new HashMap<>();
        log.info("Start to collect {} targets' class information.", targets.size());
        Map<String, List<String>> moduleClasses = null;

        for (String path : targets) {
            List<JavaSootClass> classList = SootUpViewManager.getInstance().getView().getClasses().collect(Collectors.toList());
            classList.parallelStream().forEach(c -> {
                results.put(c.getName(), collector.collectSP(c));
            });
        }
        log.info("Total {} classes.", results.size());
        return results;
    }

    public Map<String, CompletableFuture<ClassReference>> loadAndExtract(List<String> targets) {
        Map<String, CompletableFuture<ClassReference>> results = new HashMap<>();
        log.info("Start to collect {} targets' class information.", targets.size());

        Map<String, List<String>> moduleClasses = null;
        if (!GlobalConfiguration.IS_USING_SETTING_JRE) {
            moduleClasses = ModulePathSourceLocator.v().getClassUnderModulePath("jrt:/");
        }

        for (final String path : targets) {
            List<String> classes = SemanticUtils.getTargetClasses(path, moduleClasses);

            if (classes == null) continue;

            for (String cl : classes) {
                try {
                    SootClass theClass = Scene.v().loadClassAndSupport(cl); // TODO debug
//                    if(!cl.startsWith("")){
//                        continue;
//                    }
                    if (!theClass.isPhantom()) {
                        // 这里存在类数量不一致的情况，是因为存在重复的对象
                        results.put(cl, collector.collect(theClass));
                        theClass.setApplicationClass();
                    }
                } catch (Exception e) {
                    log.error("Load Error: {}, Message: {}", cl, e.getMessage());
//                    e.printStackTrace();
                }
            }
        }
        log.info("Total {} classes.", results.size());
        return results;
    }

    public void transform(Collection<CompletableFuture<ClassReference>> futures) {
        int count = 0;
        for (CompletableFuture<ClassReference> future : futures) {
            try {
                ClassReference classRef = future.get();
                if (count % 10000 == 0) {
                    log.info("Collected {} classes' information", count);
                }
                count++;
                if (classRef == null) continue;

                dataContainer.store(classRef, false);
            } catch (InterruptedException | ExecutionException e) {
                log.error(e.getMessage());
//                e.printStackTrace();
                // 异步获取出错
            }
        }
        log.info("Collected {} classes' information", futures.size());
    }

    public void buildClassEdges(List<String> classes) {
        int counter = 0;
        int total = classes.size();
        log.info("Build {} classes' edges.", total);
        for (String cls : classes) {
            if (counter % 10000 == 0) {
                log.info("Build {}/{} classes.", counter, total);
            }
            counter++;
            ClassReference clsRef = dataContainer.getClassRefByName(cls, IS_BUILD_WIH_CACHE_ENABLE);
            if (clsRef == null) continue;
            ClassInfoCollector.collectRelationInfo(clsRef, IS_BUILD_WIH_CACHE_ENABLE, false, dataContainer, rulesContainer);
        }
        log.info("Build {}/{} classes.", counter, total);
    }

    public void buildClassEdgesSP(List<String> classes) {
        int counter = 0;
        int total = classes.size();
        log.info("Build {} classes' edges.", total);
        for (String cls : classes) {
            if (counter % 10000 == 0) {
                log.info("Build {}/{} classes.", counter, total);
            }
            counter++;
            ClassReference clsRef = dataContainer.getClassRefByName(cls, IS_BUILD_WIH_CACHE_ENABLE);
            if (clsRef == null) continue;
            ClassInfoCollector.collectRelationInfoSP(clsRef, IS_BUILD_WIH_CACHE_ENABLE, false, dataContainer, rulesContainer);
        }
        log.info("Build {}/{} classes.", counter, total);
    }

    public void save() {
        if (GlobalConfiguration.GLOBAL_FORCE_STOP) return;
        log.info("Save data to database.");
        dataContainer.save("class");
        log.info("Graphdb saved.");
    }

}