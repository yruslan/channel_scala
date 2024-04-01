/*
 * Copyright (c) 2020 Ruslan Yushchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import sbt._

object ScalacOptions {
  val scalacOptionsForAllVersions = Seq(
    "-encoding", "UTF-8",         // Source files are in UTF-8
    "-unchecked",                 // Warn about unchecked type parameters
    "-deprecation",               // Warn about use of deprecated APIs
    "-feature",                   // Warn about misused language features
    "-explaintypes"               // Explain type errors in more detail
  )

  val compilerWarningOptions = Seq(
    "-opt-warnings",   // Enable optimizer warnings
    "-opt:l:inline",   // Enable inlining
    "-opt:l:method",   // Enable method-local optimizations
    "-opt-inline-from:com.github.yruslan.channel.**" // Enable inlining within the livrary package
  )

  lazy val scalacOptions211 = scalacOptionsForAllVersions ++
    Seq(
      "-Xsource:2.11",           // Treat compiler input as Scala source for scala-2.11
      "-target:jvm-1.8"          // Target JVM 1.8
    )

  lazy val scalacOptions212 = scalacOptionsForAllVersions ++ compilerWarningOptions ++
    Seq(
      "-Xsource:2.12",           // Treat compiler input as Scala source for scala-2.12
      "-release:8"               // Target JVM 1.8
    )

  lazy val scalacOptions213 = scalacOptionsForAllVersions ++ compilerWarningOptions ++
    Seq(
      "-Xsource:2.13",           // Treat compiler input as Scala source for scala-2.13
      "-release:8"               // Target JVM 1.8
    )

  def scalacOptionsFor(scalaVersion: String): Seq[String] = {
    val scalacOptions = CrossVersion.partialVersion(scalaVersion) match {
      case Some((2, minor)) if minor >= 13 =>
        scalacOptions213
      case Some((2, minor)) if minor == 12 =>
        scalacOptions212
      case _ =>
        scalacOptions211
    }
    println(s"Scala $scalaVersion compiler options: ${scalacOptions.mkString(" ")}")
    scalacOptions
  }
}
