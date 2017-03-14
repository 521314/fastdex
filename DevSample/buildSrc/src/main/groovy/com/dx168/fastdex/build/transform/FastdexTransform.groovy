package com.dx168.fastdex.build.transform

import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformException
import com.android.build.api.transform.TransformInvocation
import com.android.build.gradle.internal.pipeline.TransformInvocationBuilder
import com.dx168.fastdex.build.util.ClassInjectFileVisitor
import com.dx168.fastdex.build.util.Constant
import com.dx168.fastdex.build.util.FastdexUtils
import com.dx168.fastdex.build.util.GradleUtils
import com.dx168.fastdex.build.util.JavaDirDiff
import org.gradle.api.Project
import com.android.build.api.transform.Format
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import com.dx168.fastdex.build.util.FileUtils

/**
 * Created by tong on 17/10/3.
 */
class FastdexTransform extends TransformProxy {
    Project project
    String variantName
    String manifestPath

    FastdexTransform(Transform base, Project project, String variantName,String manifestPath) {
        super(base)
        this.project = project
        this.variantName = variantName
        this.manifestPath = manifestPath
    }

    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, IOException, InterruptedException {
        if (FastdexUtils.hasValidCache(project,variantName)) {
            //compare changed class
            Set<String> result = new HashSet<>()
            String[] srcDirs = project.android.sourceSets.main.java.srcDirs
            File snapshootDir = new File(FastdexUtils.getBuildDir(project,variantName),Constant.FASTDEX_SNAPSHOOT_DIR)

            Set<String> changedJavaClassNames = new HashSet<>()
            for (String srcDir : srcDirs) {
                File newDir = new File(srcDir)
                File oldDir = new File(snapshootDir,srcDir)

                changedJavaClassNames.addAll(JavaDirDiff.diff(newDir,oldDir,true,project.logger))
            }
            if (changedJavaClassNames.isEmpty()) {
                base.transform(transformInvocation)
                return
            }

            changedJavaClassNames.add(GradleUtils.getBuildConfigClassName(manifestPath))

            //add all changed file to jar
            File mergedJar = new File(FastdexUtils.getBuildDir(project,variantName),"latest-merged.jar")
            FileUtils.deleteFile(mergedJar)
            GradleUtils.executeMerge(transformInvocation,mergedJar)

            File classesDir = new File(FastdexUtils.getBuildDir(project,variantName),"patch-" + Constant.FASTDEX_CLASSES_DIR)
            FileUtils.deleteDir(classesDir)
            FileUtils.ensumeDir(classesDir)

            project.copy {
                from project.zipTree(mergedJar)
                for (String className : changedJavaClassNames) {
                    include className
                }

                into classesDir
            }
            FileUtils.deleteFile(mergedJar)

            File patchJar = new File(FastdexUtils.getBuildDir(project,variantName),"patch-combined.jar")
            project.ant.zip(baseDir: classesDir, destFile: patchJar)

            project.logger.error("==fastdex will generate dex file ${changedJavaClassNames}")

            String dxcmd = "${project.android.getSdkDirectory()}/build-tools/${project.android.getBuildToolsVersion()}/dx"

            File patchDexFile = new File(FastdexUtils.getBuildDir(project,variantName),"patch.dex")
            FileUtils.deleteFile(patchDexFile)
            dxcmd = "${dxcmd} --dex --output=${patchDexFile} ${patchJar}"
            project.logger.error("==fastdex generate dex cmd \n" + dxcmd)
            def process = dxcmd.execute()
            int status = process.waitFor()
            process.destroy()
            if (status == 0) {
                //dexelements [fastdex-runtime.dex fastdex-antilazyload.dex patch.dex ${dex_cache}.listFiles]
                File dexOutputDir = getDexOutputDir(transformInvocation)
                FileUtils.cleanDir(dexOutputDir)
                File cacheDexDir = FastdexUtils.getDexCacheDir(project,variantName)

                //runtime.dex            => classes.dex
                //antilazyload.dex       => classes2.dex
                //patch.dex              => classes3.dex
                //dex_cache.classes.dex  => classes4.dex
                //dex_cache.classes2.dex => classes5.dex

                //copy fastdex-runtime.dex
                FileUtils.copyResourceUsingStream(Constant.RUNTIME_DEX_FILENAME,new File(dexOutputDir,"classes.dex"))
                //copy fastdex-antilazyload.dex
                FileUtils.copyResourceUsingStream(Constant.ANTILAZYLOAD_DEX_FILENAME,new File(dexOutputDir,"classes2.dex"))
                //copy patch.dex
                FileUtils.copyFileUsingStream(patchDexFile,new File(dexOutputDir,"classes3.dex"))
                FileUtils.copyFileUsingStream(new File(cacheDexDir,"classes.dex"),new File(dexOutputDir,"classes4.dex"))

                int point = 2
                File dexFile = new File(cacheDexDir,"classes" + point + ".dex")
                while (FileUtils.isLegalFile(dexFile.getAbsolutePath())) {
                    FileUtils.copyFileUsingStream(dexFile,new File(dexOutputDir,"classes" + (point + 3) + ".dex"))
                    point++
                    dexFile = new File(cacheDexDir,"classes" + point + ".dex")
                }

                //log
                StringBuilder sb = new StringBuilder()
                sb.append("cached_dex[")
                File[] dexFiles = cacheDexDir.listFiles()
                for (File file : dexFiles) {
                    if (file.getName().endsWith(Constant.DEX_SUFFIX)) {
                        sb.append(file.getName())
                        if (file != dexFiles[dexFiles.length - 1]) {
                            sb.append(",")
                        }
                    }
                }
                sb.append("] cur-dex[")
                dexFiles = dexOutputDir.listFiles()
                for (File file : dexFiles) {
                    if (file.getName().endsWith(Constant.DEX_SUFFIX)) {
                        sb.append(file.getName())
                        if (file != dexFiles[dexFiles.length - 1]) {
                            sb.append(",")
                        }
                    }
                }
                sb.append("]")
                project.logger.error("==fastdex ${sb}")
            }
            else {
                //fail
                base.transform(transformInvocation)
            }
        }
        else {
            FileUtils.deleteDir(FastdexUtils.getBuildDir(project,variantName))
            File mergedJar = new File(FastdexUtils.getBuildDir(project,variantName),"merged.jar")
            File outJar = new File(FastdexUtils.getBuildDir(project,variantName),"injected.jar")

            injectAndMergeJar(transformInvocation,mergedJar,outJar)
            createSourceSetSnapshoot()
            keepDependenciesList()

            //invoke the original transform method
            TransformInvocationBuilder builder = new TransformInvocationBuilder(transformInvocation.getContext());
            builder.addInputs(jarFileToInputs(outJar))
            builder.addOutputProvider(transformInvocation.getOutputProvider())
            builder.addReferencedInputs(transformInvocation.getReferencedInputs())
            builder.addSecondaryInputs(transformInvocation.getSecondaryInputs())
            builder.setIncrementalMode(transformInvocation.isIncremental())
            base.transform(builder.build())

            //save dex
            copyDex(transformInvocation)
            //save R.txt
            copyRTxt()
        }
    }

    void copyRTxt() {
        File sourceFile = new File(project.getBuildDir(),"/intermediates/symbols/${variantName}/R.txt")
        File destFile = new File(FastdexUtils.getBuildDir(project,variantName),Constant.R_TXT)
        FileUtils.copyFileUsingStream(sourceFile,destFile)
    }

    void createSourceSetSnapshoot() {
        String[] srcDirs = project.android.sourceSets.main.java.srcDirs
        File snapshootDir = new File(FastdexUtils.getBuildDir(project,variantName),Constant.FASTDEX_SNAPSHOOT_DIR)
        FileUtils.ensumeDir(snapshootDir)
        for (String srcDir : srcDirs) {
//            project.copy {
//                from(new File(srcDir))
//                into(new File(snapshootDir,srcDir))
//            }

            FileUtils.copyDir(new File(srcDir),new File(snapshootDir,srcDir),Constant.JAVA_SUFFIX)
        }
    }

    void keepDependenciesList() {
        Set<String> dependenciesList = GradleUtils.getCurrentDependList(project,variantName)
        StringBuilder sb = new StringBuilder()
        dependenciesList.each {
            sb.append(it)
            sb.append("\n")
        }

        File dependenciesListFile = new File(FastdexUtils.getBuildDir(project,variantName),Constant.DEPENDENCIES_MAPPING_FILENAME);
        FileUtils.write2file(sb.toString().getBytes(),dependenciesListFile)
    }

    Set<String> getCachedDependenciesList() {
        Set<String> result = new HashSet<>()
        File dependenciesListFile = new File(FastdexUtils.getBuildDir(project,variantName),Constant.DEPENDENCIES_MAPPING_FILENAME);
        if (FileUtils.isLegalFile(dependenciesListFile.getAbsolutePath())) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(dependenciesListFile)))
            String line = null
            while ((line = reader.readLine()) != null) {
                result.add(line)
            }
            reader.close()
        }
        return result
    }

    File getDexOutputDir(TransformInvocation transformInvocation) {
        def outputProvider = transformInvocation.getOutputProvider();
        File outputDir = null;
        try {
            outputDir = outputProvider.getContentLocation("main", base.getOutputTypes(), base.getScopes(), Format.DIRECTORY);
        } catch (Throwable e) {
            e.printStackTrace()
        }

        return outputDir;
    }

    void copyDex(TransformInvocation transformInvocation) {
        File dexOutputDir = getDexOutputDir(transformInvocation)

        project.logger.error("==fastdex dex output directory: " + dexOutputDir)

        File cacheDexDir = FastdexUtils.getDexCacheDir(project,variantName)
        File[] files = dexOutputDir.listFiles()
        files.each { file ->
            if (file.getName().endsWith(".dex")) {
                FileUtils.copyFileUsingStream(file,new File(cacheDexDir,file.getName()))
            }
        }

        //dexelements [fastdex-runtime.dex fastdex-antilazyload.dex ${dex_cache}.listFiles]
        FileUtils.cleanDir(dexOutputDir)

        //runtime.dex            => classes.dex
        //antilazyload.dex       => classes2.dex
        //dex_cache.classes.dex  => classes3.dex
        //dex_cache.classes2.dex => classes4.dex

        //copy fastdex-runtime.dex
        FileUtils.copyResourceUsingStream(Constant.RUNTIME_DEX_FILENAME,new File(dexOutputDir,"classes.dex"))
        //copy fastdex-antilazyload.dex
        FileUtils.copyResourceUsingStream(Constant.ANTILAZYLOAD_DEX_FILENAME,new File(dexOutputDir,"classes2.dex"))

        FileUtils.copyFileUsingStream(new File(cacheDexDir,"classes.dex"),new File(dexOutputDir,"classes3.dex"))
        int point = 2
        File dexFile = new File(cacheDexDir,"classes" + point + ".dex")
        while (FileUtils.isLegalFile(dexFile.getAbsolutePath())) {
            FileUtils.copyFileUsingStream(dexFile,new File(dexOutputDir,"classes" + (point + 2) + ".dex"))
            point++
            dexFile = new File(cacheDexDir,"classes" + point + ".dex")
        }

        //log
        StringBuilder sb = new StringBuilder()
        sb.append("cached_dex[")
        File[] dexFiles = cacheDexDir.listFiles()
        for (File file : dexFiles) {
            if (file.getName().endsWith(Constant.DEX_SUFFIX)) {
                sb.append(file.getName())
                if (file != dexFiles[dexFiles.length - 1]) {
                    sb.append(",")
                }
            }
        }
        sb.append("] cur-dex[")
        dexFiles = dexOutputDir.listFiles()
        for (File file : dexFiles) {
            if (file.getName().endsWith(Constant.DEX_SUFFIX)) {
                sb.append(file.getName())
                if (file != dexFiles[dexFiles.length - 1]) {
                    sb.append(",")
                }
            }
        }
        sb.append("]")
        project.logger.error("==fastdex first build ${sb}")
    }

    void injectAndMergeJar(TransformInvocation transformInvocation,File mergedJar,File outJar) {
        GradleUtils.executeMerge(transformInvocation,mergedJar)

        //unzip merged.jar
        File unzipDir = new File(FastdexUtils.getBuildDir(project,variantName),"merged")

        project.copy {
            from project.zipTree(mergedJar)
            into unzipDir
        }

        Set<String> sourceSetJavaFiles = scanAllJavaFileInSourceSet()
        //project.logger.error("==fastdex sourceSetJavaFiles: " + sourceSetJavaFiles)

        File classesDir = new File(FastdexUtils.getBuildDir(project,variantName),Constant.FASTDEX_CLASSES_DIR)
        FileUtils.ensumeDir(classesDir)
        Files.walkFileTree(unzipDir.toPath(),new ClassInjectFileVisitor(project,sourceSetJavaFiles,unzipDir.toPath(),classesDir.toPath()))

        project.logger.error("==fastdex inject complete")

        project.ant.zip(baseDir: classesDir, destFile: outJar)
    }

    Set<String> scanAllJavaFileInSourceSet() {
        /**
         source dir
         ├── com
         │   └── dx168
         │       └── fastdex
         │           └── sample
         │               ├── Application.class
         │               ├── BuildConfig.class
         │               └── MainActivity.class
         └── rx
         ├── Observable.class
         └── Scheduler.class

         result =>
         com.dx168.fastdex.sample.Application
         com.dx168.fastdex.sample.BuildConfig
         com.dx168.fastdex.sample.MainActivity
         rx.Observable
         rx.Scheduler
         */
        Set<String> result = new HashSet<>();
        List<String> srcLists = new ArrayList<>()
        for (String srcDir : project.android.sourceSets.main.java.srcDirs) {
            srcLists.add(srcDir);
        }

        def variantStr = variantName.toLowerCase()
        File aptDir = new File(project.getBuildDir(),"/generated/source/apt/${variantStr}")
        if (FileUtils.dirExists(aptDir.getAbsolutePath())) {
            srcLists.add(aptDir.getAbsolutePath())
        }

        File buildConfigDir = new File(project.getBuildDir(),"/generated/source/buildConfig/${variantStr}")
        if (FileUtils.dirExists(buildConfigDir.getAbsolutePath())) {
            srcLists.add(buildConfigDir.getAbsolutePath())
        }

        for (String srcDir : srcLists) {
            project.logger.error("==fastdex sourceSet: " + srcDir)

            Path srcDirPath = new File(srcDir).toPath()
            Files.walkFileTree(srcDirPath,new SimpleFileVisitor<Path>(){
                @Override
                FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!file.toFile().getName().endsWith(Constant.JAVA_SUFFIX)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relativePath = srcDirPath.relativize(file)

                    String className = relativePath.toString()
                    className = className.substring(0,className.length() - Constant.JAVA_SUFFIX.length())
                    result.add(className)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return result
    }
}