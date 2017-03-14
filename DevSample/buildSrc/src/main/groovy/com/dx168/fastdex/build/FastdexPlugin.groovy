package com.dx168.fastdex.build

import com.android.build.api.transform.Transform
import com.android.build.gradle.internal.pipeline.TransformTask
import com.android.build.gradle.internal.transforms.DexTransform
import com.dx168.fastdex.build.task.FastdexCleanTask
import com.dx168.fastdex.build.task.FastdexCreateMaindexlistFileTask
import com.dx168.fastdex.build.task.FastdexManifestTask
import com.dx168.fastdex.build.task.FastdexPrepareTask
import com.dx168.fastdex.build.task.FastdexResourceIdTask
import com.dx168.fastdex.build.util.BuildTimeListener
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.execution.TaskExecutionGraphListener
import java.lang.reflect.Field
import com.dx168.fastdex.build.transform.FastdexTransform
import com.dx168.fastdex.build.extension.FastdexExtension

/**
 * Registers the plugin's tasks.
 * Created by tong on 17/10/3.
 */
class FastdexPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.gradle.addListener(new BuildTimeListener())

        project.extensions.create('fastdex', FastdexExtension)
        def configuration = project.fastdex

        project.afterEvaluate {
            if (!project.plugins.hasPlugin('com.android.application')) {
                throw new GradleException('generateTinkerApk: Android Application plugin required')
            }

            def android = project.extensions.android
            //open jumboMode
            android.dexOptions.jumboMode = true
            //close preDexLibraries
            try {
                android.dexOptions.preDexLibraries = false
            } catch (Throwable e) {
                //no preDexLibraries field, just continue
            }

            project.tasks.create("fastdexCleanAll", FastdexCleanTask)

            android.applicationVariants.all { variant ->
                def variantOutput = variant.outputs.first()
                def variantName = variant.name.capitalize()

                try {
                    def instantRunTask = project.tasks.getByName("transformClassesWithInstantRunFor${variantName}")
                    if (instantRunTask) {
                        throw new GradleException(
                                "Fastdex does not support instant run mode, please trigger build"
                                        + " by assemble${variantName} or disable instant run"
                                        + " in 'File->Settings...'."
                        )
                    }
                } catch (UnknownTaskException e) {
                    // Not in instant run mode, continue.
                }

                def minifyEnabled = android.buildTypes["${variantName.toLowerCase()}"].minifyEnabled
                if (minifyEnabled) {
                    project.logger.error("==fastdex disable fastdex [android.buildTypes${variantName.toLowerCase()}.minifyEnabled=true]")
                }
                else {
                    //clean cache task(user trigger)
                    FastdexCleanTask cleanTask = project.tasks.create("fastdexCleanFor${variantName}", FastdexCleanTask)
                    cleanTask.variantName = variantName

                    //prepare context
                    FastdexPrepareTask prepareTask = project.tasks.create("fastdexPrepareTaskFor${variantName}", FastdexPrepareTask)
                    prepareTask.variantName = variantName
                    variant.assemble.dependsOn prepareTask

                    Task multidexlistTask = project.tasks.getByName("transformClassesWithMultidexlistFor${variantName}")
                    if (multidexlistTask != null) {
                        FastdexCreateMaindexlistFileTask createFileTask = project.tasks.create("fastdexCreate${variantName}MaindexlistFileTask", FastdexCreateMaindexlistFileTask)
                        createFileTask.variantName = variantName

                        multidexlistTask.dependsOn createFileTask
                        multidexlistTask.enabled = false
                    }

                    FastdexManifestTask manifestTask = project.tasks.create("fastdexProcess${variantName}Manifest", FastdexManifestTask)
                    manifestTask.manifestPath = variantOutput.processManifest.manifestOutputFile
                    manifestTask.variantName = variantName
                    manifestTask.mustRunAfter variantOutput.processManifest

                    variantOutput.processResources.dependsOn manifestTask

                    //resource id
                    FastdexResourceIdTask applyResourceTask = project.tasks.create("fastdexProcess${variantName}ResourceId", FastdexResourceIdTask)
                    applyResourceTask.resDir = variantOutput.processResources.resDir
                    applyResourceTask.variantName = variantName
                    //let applyResourceTask run after manifestTask
                    applyResourceTask.mustRunAfter manifestTask
                    variantOutput.processResources.dependsOn applyResourceTask

                    project.getGradle().getTaskGraph().addTaskExecutionGraphListener(new TaskExecutionGraphListener() {
                        @Override
                        public void graphPopulated(TaskExecutionGraph taskGraph) {
                            for (Task task : taskGraph.getAllTasks()) {
                                if (task.getProject().equals(project)
                                        && task instanceof TransformTask
                                        && task.name.toLowerCase().contains(variant.name.toLowerCase())) {

                                    Transform transform = ((TransformTask) task).getTransform()
                                    if ((((transform instanceof DexTransform) || transform.getName().equals("dex"))
                                            && !(transform instanceof FastdexTransform))) {

                                        String manifestPath = variantOutput.processManifest.manifestOutputFile
                                        FastdexTransform fastdexTransform = new FastdexTransform(task.transform,project,variantName,manifestPath)
                                        Field field = getFieldByName(task.getClass(),'transform')
                                        field.setAccessible(true)
                                        field.set(task,fastdexTransform)
                                    }
                                }
                            }
                        }
                    });
                }
            }
        }
    }

    Field getFieldByName(Class<?> aClass, String name) {
        Class<?> currentClass = aClass;
        while (currentClass != null) {
            try {
                return currentClass.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                // ignored.
            }
            currentClass = currentClass.getSuperclass();
        }
        return null;
    }
}