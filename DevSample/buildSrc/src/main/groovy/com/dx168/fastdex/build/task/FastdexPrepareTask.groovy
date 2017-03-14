package com.dx168.fastdex.build.task

import com.dx168.fastdex.build.util.FastdexUtils
import com.dx168.fastdex.build.util.FileUtils
import com.dx168.fastdex.build.util.GradleUtils
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * Created by tong on 17/3/12.
 */
public class FastdexPrepareTask extends DefaultTask {
    String variantName

    FastdexPrepareTask() {
        group = 'fastdex'
    }

    @TaskAction
    void prepareEnv() {
        //delete expired cache
        boolean hasValidCache = FastdexUtils.hasValidCache(project,variantName)
        if (hasValidCache) {
            try {
                File cachedDependListFile = FastdexUtils.getCachedDependListFile(project,variantName)
                if (!FileUtils.isLegalFile(cachedDependListFile)) {
                    throw new CheckException("==fastdex miss depend list file: ${cachedDependListFile}")
                }
                //old
                Set<String> cachedDependencies = getCachedDependList()
                //current
                Set<String> currentDependencies = GradleUtils.getCurrentDependList(project,variantName)
                currentDependencies.removeAll(cachedDependencies)

                //check dependencies
                //remove
                //old    current
                //1.aar  1.aar
                //2.aar

                //add
                //old    current
                //1.aar  1.aar
                //       2.aar

                //change
                //old    current
                //1.aar  1.aar
                //2.aar  xx.aar

                //handler add and change
                if (!currentDependencies.isEmpty()) {
                    throw new CheckException("==fastdex ${variantName.toLowerCase()} dependencies changed")
                }

                File cachedResourceMappingFile = FastdexUtils.getCachedResourceMappingFile(project,variantName)
                if (!FileUtils.isLegalFile(cachedResourceMappingFile)) {
                    throw new CheckException("==fastdex miss resource mapping file: ${cachedResourceMappingFile}")
                }
            } catch (CheckException e) {
                hasValidCache = false
                project.logger.error("==fastdex ${e.getMessage()}")
                project.logger.error("==fastdex we will remove ${variantName.toLowerCase()} cache")
            }
        }
        if (hasValidCache) {
            project.logger.error("==fastdex discover cached for ${variantName.toLowerCase()}")
        }
        else {
            FastdexUtils.cleanCache(project,variantName)
            FileUtils.ensumeDir(FastdexUtils.getBuildDir(project,variantName))
        }
    }

    /**
     * 获取缓存的依赖列表
     * @return
     * @throws FileNotFoundException
     */
    Set<String> getCachedDependList() {
        Set<String> result = new HashSet<>()
        File cachedDependListFile = FastdexUtils.getCachedDependListFile(project,variantName)
        if (FileUtils.isLegalFile(cachedDependListFile.getAbsolutePath())) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(cachedDependListFile)))
            String line = null
            while ((line = reader.readLine()) != null) {
                result.add(line)
            }
            reader.close()
        }
        return result
    }


    private class CheckException extends Exception {
        CheckException(String var1) {
            super(var1)
        }
    }
}
