lazy val scala211 = "2.11.12"
lazy val scala212 = "2.12.12"
lazy val scala213 = "2.13.3"

name := "channel"
organization := "com.github.yruslan"
version := "0.0.1-SNAPSHOT"

scalaVersion := scala211
crossScalaVersions := Seq(scala211, scala212, scala213)

// Change this to another test framework if you prefer
libraryDependencies += "org.scalatest" %% "scalatest" % "3.1.4" % "test"
