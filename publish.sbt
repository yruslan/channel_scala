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

ThisBuild / scmInfo := Some(
  ScmInfo(
    browseUrl = url("https://github.com/yruslan/channel_scala/tree/master"),
    connection = "scm:git:git://github.com/yruslan/channel_scala.git",
    devConnection = "scm:git:ssh://github.com/yruslan/channel_scala.git"
  )
)

ThisBuild / developers := List(
  Developer(
    id    = "yruslan",
    name  = "Ruslan Iushchenko",
    email = "yruslan@gmail.com",
    url   = url("https://github.com/yruslan")
  )
)

ThisBuild / homepage := Some(url("https://github.com/yruslan/channel_scala"))
ThisBuild / description := "A port of GoLang channels to Scala"
ThisBuild / startYear := Some(2020)
ThisBuild / licenses += "MIT" -> url("http://opensource.org/licenses/MIT")

ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value) {
    Some("snapshots" at s"${nexus}content/repositories/snapshots")
  } else {
    Some("releases" at s"${nexus}service/local/staging/deploy/maven2")
  }
}
ThisBuild / publishMavenStyle := true
