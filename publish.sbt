/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 Ruslan Yushchenko
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * For more information, please refer to <http://opensource.org/licenses/MIT>
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
