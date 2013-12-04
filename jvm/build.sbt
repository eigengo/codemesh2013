organization := "org.eigengo"

name := "codemesh2013"

version := "1.0.0"

scalaVersion := "2.10.2"

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-Xlint",
  "-language:_",
  "-target:jvm-1.7",
  "-encoding", "UTF-8"
)

resolvers ++= Seq(
  "Spray Releases" at "http://repo.spray.io",
  "Spray Releases" at "http://repo.spray.io",
  "Xugggler"       at "http://xuggle.googlecode.com/svn/trunk/repo/share/java/"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka"       %% "akka-actor"         % "2.2.3",
  "com.typesafe.akka"       %% "akka-slf4j"         % "2.2.3",
  "ch.qos.logback"           % "logback-classic"    % "1.0.11",
  "com.github.sstone"       %% "amqp-client"        % "1.3-ML1",
  "xuggle"                   % "xuggle-xuggler"     % "5.4",
  "io.spray"                 % "spray-routing"      % "1.2.0",
  "io.spray"                 % "spray-client"       % "1.2.0",
  "io.spray"                %% "spray-json"         % "1.2.3",
  "io.spray"                 % "spray-testkit"      % "1.2.0"    % "test",
  "com.typesafe.akka"       %% "akka-testkit"       % "2.2.3"     % "test"
)

parallelExecution in Test := false

transitiveClassifiers := Seq("sources")

initialCommands in console := "import org.eigengo.cm._,akka.actor._"

initialCommands in (Test, console) <<= (initialCommands in console)(_ + ",akka.testkit._")
