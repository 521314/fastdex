package com.dx168.fastdex.build.task

import com.dx168.fastdex.build.util.FastdexUtils
import com.dx168.fastdex.build.util.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * Created by tong on 17/3/12.
 */
public class FastdexCleanTask extends DefaultTask {
    String variantName

    FastdexCleanTask() {
        group = 'fastdex'
    }

    @TaskAction
    void clean() {
        if (variantName == null) {
            FastdexUtils.cleanAllCache()
        }
        else {
            FastdexUtils.cleanCache(project,variantName)
        }
    }
}
