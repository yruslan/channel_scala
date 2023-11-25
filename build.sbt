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

lazy val scala211 = "2.11.12"
lazy val scala212 = "2.12.18"
lazy val scala213 = "2.13.12"

name := "channel_scala"
organization := "com.github.yruslan"

scalaVersion := scala211
crossScalaVersions := Seq(scala211, scala212, scala213)

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.15" % "test"

releasePublishArtifactsAction := PgpKeys.publishSigned.value

releaseCrossBuild := true
addCommandAlias("releaseNow", ";set releaseVersionBump := sbtrelease.Version.Bump.Bugfix; release with-defaults")
