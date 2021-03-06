// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package training.util

import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import org.jetbrains.concurrency.AsyncPromise
import training.commands.kotlin.TaskTestContext
import training.learn.CourseManager
import training.learn.interfaces.Lesson
import training.learn.lesson.LessonListener
import training.learn.lesson.kimpl.KLesson
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val LOG: Logger = Logger.getInstance(LearningLessonsAutoExecutor::class.java)

@Suppress("HardCodedStringLiteral")
class LearningLessonsAutoExecutor(val project: Project, private val progress: ProgressIndicator) {
  private fun runSingleLesson(lesson: Lesson) {
    invokeAndWaitIfNeeded {
      CourseManager.instance.openLesson(project, lesson)
    }
    executeLesson(lesson)
  }

  private fun runAllLessons() {
    val lessons = CourseManager.instance.lessonsForModules

    for (lesson in lessons) {
      if (lesson !is KLesson) continue
      progress.checkCanceled()
      invokeAndWaitIfNeeded {
        CourseManager.instance.openLesson(project, lesson)
      }
      try {
        executeLesson(lesson)
      }
      catch (e: TimeoutException) {
        // Check lesson state later
      }
      if (!lesson.passed) {
        LOG.error("Can't pass lesson " + lesson.name)
      }
    }
  }

  private fun executeLesson(lesson: Lesson) {
    val lessonPromise = AsyncPromise<Boolean>()
    lesson.addLessonListener(object : LessonListener {
      override fun lessonPassed(lesson: Lesson) {
        lessonPromise.setResult(true)
      }
    })
    progress.checkCanceled()
    TaskTestContext.inTestMode = true
    try {
      val passedStatus = lessonPromise.blockingGet(lesson.testScriptProperties.duration, TimeUnit.SECONDS)
      if (passedStatus == null || !passedStatus) {
        LOG.error("Can't pass lesson " + lesson.name)
      }
      else {
        System.err.println("Passed " + lesson.name)
      }
    }
    finally {
      TaskTestContext.inTestMode = false
    }
  }

  companion object {
    fun runAllLessons(project: Project) {
      runBackgroundableTask("Running all lessons", project) {
        try {
          val learningLessonsAutoExecutor = LearningLessonsAutoExecutor(project, it)
          learningLessonsAutoExecutor.runAllLessons()
        }
        finally {
          TaskTestContext.inTestMode = false
        }
      }
    }

    fun runSingleLesson(project: Project, lesson: Lesson) {
      runBackgroundableTask("Running lesson ${lesson.name}", project) {
        try {
          val learningLessonsAutoExecutor = LearningLessonsAutoExecutor(project, it)
          learningLessonsAutoExecutor.runSingleLesson(lesson)
        }
        finally {
          TaskTestContext.inTestMode = false
        }
      }
    }
  }
}