package net.minecraftforge.gradle;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.process.ExecSpec;
import org.gradle.process.JavaExecSpec;

import java.lang.reflect.Method;

public class ExecHelper {
    private static final ExecInvoker EXEC_INVOKER = GradleVersionUtils.choose("9.0",
            OldExecInvoker::new, NewExecInvoker::new);

    interface ExecInvoker {
        ExecResult exec(Project project, Action<? super ExecSpec> action);
        ExecResult javaexec(Project project, Action<? super JavaExecSpec> action);
    }

    private static class OldExecInvoker implements ExecInvoker {
        private static final Method execMethod;
        private static final Method javaexecMethod;

        static {
            try {
                execMethod = Project.class.getMethod("exec", Action.class);
                javaexecMethod = Project.class.getMethod("javaexec", Action.class);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public ExecResult exec(Project project, Action<? super ExecSpec> action) {
            return ReflectionHelper.call(execMethod, project, action);
        }

        @Override
        public ExecResult javaexec(Project project, Action<? super JavaExecSpec> action) {
            return ReflectionHelper.call(javaexecMethod, project, action);
        }
    }

    private static class NewExecInvoker implements ExecInvoker {
        private static final Method getServicesMethod;
        private static final Method getMethod;

        static {
            try {
                Class<?> projectInternalClass = Class.forName("org.gradle.api.internal.project.ProjectInternal");
                getServicesMethod = projectInternalClass.getMethod("getServices");

                Class<?> serviceRegistryClass = Class.forName("org.gradle.internal.service.ServiceRegistry");
                getMethod = serviceRegistryClass.getMethod("get", Class.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize NewExecInvoker reflection", e);
            }
        }

        private ExecOperations getExecOperations(Project project) {
            try {
                Object serviceRegistry = getServicesMethod.invoke(project);
                return (ExecOperations) getMethod.invoke(serviceRegistry, ExecOperations.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get ExecOperations service", e);
            }
        }

        @Override
        public ExecResult exec(Project project, Action<? super ExecSpec> action) {
            return getExecOperations(project).exec(action);
        }

        @Override
        public ExecResult javaexec(Project project, Action<? super JavaExecSpec> action) {
            return getExecOperations(project).javaexec(action);
        }
    }

    public static ExecResult exec(Project project, Action<? super ExecSpec> action) {
        return EXEC_INVOKER.exec(project, action);
    }

    public static ExecResult javaexec(Project project, Action<? super JavaExecSpec> action) {
        return EXEC_INVOKER.javaexec(project, action);
    }
}
