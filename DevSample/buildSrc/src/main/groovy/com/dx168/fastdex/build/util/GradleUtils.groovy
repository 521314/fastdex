package com.dx168.fastdex.build.util

import com.google.common.collect.Lists
import org.gradle.api.Project
import com.android.build.api.transform.DirectoryInput
import com.android.build.api.transform.JarInput
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformInvocation
import com.android.build.gradle.internal.transforms.JarMerger
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import com.android.build.gradle.internal.pipeline.TransformInvocationBuilder
import com.android.build.api.transform.DirectoryInput
import com.android.build.api.transform.JarInput
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Status
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformInvocation
import com.android.build.gradle.internal.pipeline.TransformInvocationBuilder
import com.dx168.fastdex.build.util.Constant
import com.dx168.fastdex.build.util.FastdexUtils
import com.dx168.fastdex.build.util.FileUtils
import com.dx168.fastdex.build.util.GradleUtils
import com.dx168.fastdex.build.util.JavaDirDiff
import com.google.common.collect.ImmutableList
import com.android.build.api.transform.Transform
import org.gradle.api.Project



/**
 * Created by tong on 17/3/14.
 */
public class GradleUtils {
    public static Set<String> getCurrentDependList(Project project,String variantName) {
        Set<String> result = new HashSet<>()
//        project.configurations.compile.each { File file ->
//            //project.logger.error("==fastdex compile: ${file.absolutePath}")
//            result.add(file.getAbsolutePath())
//        }
//
//        project.configurations."${str}Compile".each { File file ->
//            //project.logger.error("==fastdex ${str}Compile: ${file.absolutePath}")
//            result.add(file.getAbsolutePath())
//        }

        project.configurations.all.findAll { !it.allDependencies.empty }.each { c ->
            if ("compile".equals(c.name) || "_${variantName.toLowerCase()}Compile") {
                c.allDependencies.each { dep ->
                    String depStr =  "$dep.group:$dep.name:$dep.version"
                    if (!"null:unspecified:null".equals(depStr)) {
                        result.add(depStr)
                    }
                }
            }
        }
        return result
    }

    public static String getBuildConfigClassName(String manifestPath) {
        def xml = new XmlParser().parse(new InputStreamReader(new FileInputStream(manifestPath), "utf-8"))
        String packageName = xml.attribute('package')

        return "${packageName.replaceAll("\\.","/")}/BuildConfig.class"
    }

    public static void executeMerge(TransformInvocation transformInvocation, File mergedJar) {
        List<JarInput> jarInputs = Lists.newArrayList();
        List<DirectoryInput> dirInputs = Lists.newArrayList();

        for (TransformInput input : transformInvocation.getInputs()) {
            jarInputs.addAll(input.getJarInputs());
        }

        for (TransformInput input : transformInvocation.getInputs()) {
            dirInputs.addAll(input.getDirectoryInputs());
        }

        if (dirInputs.isEmpty() && jarInputs.size() == 1) {
            //Only one jar that does not need to merge
            FileUtils.copyFileUsingStream(jarInputs.get(0).getFile(),mergedJar)
        }
        else {
            JarMerger jarMerger = getClassJarMerger(mergedJar)

            jarInputs.each { jar ->
                project.logger.error("==fastdex add jar " + jar.getFile())
                jarMerger.addJar(jar.getFile())
            }
            dirInputs.each { dir ->
                project.logger.error("==fastdex add dir " + dir)
                jarMerger.addFolder(dir.getFile())
            }

            jarMerger.close()
        }
    }


    private static JarMerger getClassJarMerger(File jarFile) {
        JarMerger jarMerger = new JarMerger(jarFile)

        Class<?> zipEntryFilterClazz
        try {
            zipEntryFilterClazz = Class.forName("com.android.builder.packaging.ZipEntryFilter")
        } catch (Throwable t) {
            zipEntryFilterClazz = Class.forName("com.android.builder.signing.SignedJarBuilder\$IZipEntryFilter")
        }

        Class<?>[] classArr = new Class[1];
        classArr[0] = zipEntryFilterClazz
        InvocationHandler handler = new InvocationHandler(){
            public Object invoke(Object proxy, Method method, Object[] args)
                    throws Throwable {
                return args[0].endsWith(Constant.CLASS_SUFFIX);
            }
        };
        Object proxy = Proxy.newProxyInstance(zipEntryFilterClazz.getClassLoader(), classArr, handler);

        jarMerger.setFilter(proxy);

        return jarMerger
    }


    public static TransformInvocation createNewTransformInvocation(Transform transform,TransformInvocation transformInvocation,File inputJar) {
        TransformInvocationBuilder builder = new TransformInvocationBuilder(transformInvocation.getContext());
        builder.addInputs(jarFileToInputs(transform,inputJar))
        builder.addOutputProvider(transformInvocation.getOutputProvider())
        builder.addReferencedInputs(transformInvocation.getReferencedInputs())
        builder.addSecondaryInputs(transformInvocation.getSecondaryInputs())
        builder.setIncrementalMode(transformInvocation.isIncremental())

        return builder.build()
    }

    /**
     * change the jar file to TransformInputs
     */
    private static Collection<TransformInput> jarFileToInputs(Transform transform,File jarFile) {
        TransformInput transformInput = new TransformInput() {
            @Override
            Collection<JarInput> getJarInputs() {
                JarInput jarInput = new JarInput() {
                    @Override
                    Status getStatus() {
                        return Status.ADDED
                    }

                    @Override
                    String getName() {
                        return jarFile.getName().substring(0,
                                jarFile.getName().length() - ".jar".length())
                    }

                    @Override
                    File getFile() {
                        return jarFile
                    }

                    @Override
                    Set<QualifiedContent.ContentType> getContentTypes() {
                        return transform.getInputTypes()
                    }

                    @Override
                    Set<QualifiedContent.Scope> getScopes() {
                        return transform.getScopes()
                    }
                }
                return ImmutableList.of(jarInput)
            }


            @Override
            Collection<DirectoryInput> getDirectoryInputs() {
                return ImmutableList.of()
            }
        }
        return ImmutableList.of(transformInput)
    }
}
