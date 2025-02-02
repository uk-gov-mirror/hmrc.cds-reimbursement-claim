import play.core.PlayVersion.current
import sbt._

object AppDependencies {
  val silencerVersion = "1.7.0"

  val compile = Seq(
    "uk.gov.hmrc"       %% "bootstrap-backend-play-27" % "3.4.0",
    "uk.gov.hmrc"       %% "simple-reactivemongo"      % "7.30.0-play-27",
    "org.typelevel"     %% "cats-core"                 % "2.3.1",
    "org.julienrf"      %% "play-json-derived-codecs"  % "7.0.0",
    "com.github.kxbmap" %% "configs"                   % "0.5.0",
    "uk.gov.hmrc"       %% "work-item-repo"            % "7.11.0-play-27",
    "ru.tinkoff"        %% "phobos-core"               % "0.9.2",
    "ai.x"              %% "play-json-extensions"      % "0.42.0",
    compilerPlugin("com.github.ghik" % "silencer-plugin" % silencerVersion cross CrossVersion.full),
    "com.github.ghik"    % "silencer-lib"              % silencerVersion % Provided cross CrossVersion.full
  )

  val test = Seq(
    "com.typesafe.play"          %% "play-test"                 % current          % Test,
    "org.scalatest"              %% "scalatest"                 % "3.2.3"          % Test,
    "org.scalamock"              %% "scalamock"                 % "5.1.0"          % Test,
    "org.scalatestplus"          %% "scalacheck-1-14"           % "3.2.0.0"        % Test,
    "com.github.alexarchambault" %% "scalacheck-shapeless_1.14" % "1.2.1"          % Test,
    "org.scalatestplus.play"     %% "scalatestplus-play"        % "4.0.3"          % Test,
    "uk.gov.hmrc"                %% "reactivemongo-test"        % "4.22.0-play-27" % Test,
    "com.vladsch.flexmark"        % "flexmark-all"              % "0.36.8"         % "test, it",
    "org.pegdown"                 % "pegdown"                   % "1.6.0"          % "test, it",
    "com.typesafe.akka"          %% "akka-testkit"              % "2.5.23"         % Test,
    "org.scala-lang.modules"     %% "scala-xml"                 % "1.3.0"          % Test
  )
}
