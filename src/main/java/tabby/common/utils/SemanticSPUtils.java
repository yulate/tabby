package tabby.common.utils;

import soot.SootClass;
import soot.SootMethod;
import sootup.core.signatures.MethodSubSignature;
import sootup.core.types.ClassType;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.types.JavaClassType;
import tabby.core.sootup.SootUpViewManager;

import java.util.Optional;

public class SemanticSPUtils {

    public static boolean hasDefaultConstructor(JavaSootClass cls) {
        return cls.getMethods().stream()
                .anyMatch(m -> m.getName().equals("<init>") && m.getParameterCount() == 0);
    }

    public static boolean isSerializableClass(JavaSootClass cls) {
        // 检查类是否实现了java.io.Serializable接口
        return cls.getInterfaces().contains("java.io.Serializable");
    }

    public static JavaSootClass getSootUpClass(String className) {
        return SootUpViewManager.getInstance().getClass(className);
    }

    public static JavaSootMethod getMethodSP(JavaSootClass cls, String subSignature) {
        // 使用Stream API找方法，而不是使用forEach+return
        Optional<JavaSootMethod> methodOpt = cls.getMethods().stream()
                .filter(m -> m.getSubSignature().equals(subSignature))
                .findFirst();

        if (methodOpt.isPresent()) {
            return methodOpt.get();
        }

        // 如果当前类没找到，查找父类
        if (cls.hasSuperclass()) {
            Optional<JavaClassType> superclass = cls.getSuperclass();
            if (superclass.isPresent()) {
                JavaSootClass javaSootClass = SootUpViewManager.getInstance().getClass(superclass.get());
                if (javaSootClass != null) {
                    JavaSootMethod superMethod = getMethodSP(javaSootClass, subSignature);
                    if (superMethod != null) {
                        return superMethod;
                    }
                }
            }
        }

        // 如果父类没找到，查找接口
        for (ClassType interfaceType : cls.getInterfaces()) {
            JavaSootClass interfaceClass = SootUpViewManager.getInstance().getClass(interfaceType);
            if (interfaceClass != null) {
                JavaSootMethod interfaceMethod = getMethodSP(interfaceClass, subSignature);
                if (interfaceMethod != null) {
                    return interfaceMethod;
                }
            }
        }

        // 如果都没找到，返回null
        return null;
    }
}
