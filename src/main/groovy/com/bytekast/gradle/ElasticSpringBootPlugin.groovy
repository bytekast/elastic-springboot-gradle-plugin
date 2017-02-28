package com.bytekast.gradle

import net.researchgate.release.ReleasePlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.credentials.AwsCredentials
import org.gradle.api.publish.maven.MavenPublication
import org.springframework.boot.gradle.plugin.SpringBootPlugin
import se.transmode.gradle.plugins.docker.DockerPlugin
import se.transmode.gradle.plugins.docker.DockerTask

class ElasticSpringBootPlugin implements Plugin<Project> {

  @Override
  void apply(Project project) {
    applyBuildScripts project
    applyPlugins project
    applyDependencies project
    applyPackaging project

    createDockerTasks project
    createDeployTasks project
    createTasksDependencies project
  }

  private static void applyBuildScripts(Project p) {
    p.buildscript {
      repositories {
        mavenCentral()
      }
      dependencies {
        classpath('org.springframework.boot:spring-boot-gradle-plugin:1.5.1.RELEASE')
        classpath('se.transmode.gradle:gradle-docker:1.2')
      }
    }
  }

  private static void applyPlugins(Project p) {
    p.plugins.apply 'application'
    p.plugins.apply 'java'
    p.plugins.apply 'groovy'
    p.plugins.apply 'maven'
    p.plugins.apply 'maven-publish'

    p.plugins.apply ReleasePlugin.class
    p.plugins.apply DockerPlugin.class
    p.plugins.apply SpringBootPlugin.class
  }

  private static void applyDependencies(Project p) {
    p.dependencies {
      compile 'org.springframework.boot:spring-boot-starter-web:1.5.1.RELEASE'
      compile 'net.logstash.logback:logstash-logback-encoder:4.7'
      testCompile 'org.springframework.boot:spring-boot-starter-test'
    }
  }

  private static void applyPackaging(Project p) {
    p.publishing {

      repositories {
        maven {
          if (project.version.endsWith('-SNAPSHOT')) {
            url AWS_MAVEN_SNAPSHOTS
          } else {
            url AWS_MAVEN_RELEASES
          }
          credentials(AwsCredentials) {
            accessKey AWS_ACCESS_KEY
            secretKey AWS_SECRET_KEY
          }
        }
      }

      publications {
        mavenJava(MavenPublication) {
          from p.components.java
        }
      }
    }
  }

  private static void createDockerTasks(Project p) {

    p.task("buildDocker", DockerTask.class) {
      push = false
      applicationName = jar.baseName
      runCommand "apt-get update"
      workingDir '/app'
      addFile jar.archivePath, "/app/${jar.archiveName}"
      exposePort 8080
      defaultCommand(['java', '-jar', jar.archiveName])
    }

    p.task('pushDocker') {
      def localTag = "${project.group}/${project.name}:${version}"
      def deployTag = "${AWS_DOCKER_REPO}/${project.name}:${version}"
      exec {
        commandLine('docker', 'tag', localTag, deployTag)
      }
      exec {
        commandLine('docker', 'push', deployTag)
      }
    }

  }

  private static void createDeployTasks(Project p) {
    p.task('prepareDeploy') {
      def deployTag = "${AWS_DOCKER_REPO}/${project.name}:${version}"
      def json = [
          AWSEBDockerrunVersion: '1',
          Image                : [
              Name  : "${deployTag}",
              Update: 'true'
          ],
          'Ports'              : [
              [ContainerPort: '8080']
          ]
      ]
      def file = File.newInstance(projectDir, 'Dockerrun.aws.json')
      file.text = groovy.json.JsonBuilder.newInstance(json).toPrettyString()
    }
  }

  private static void createTasksDependencies(Project p) {
    p.pushDocker.dependsOn p.buildDocker
    p.pushDocker.finalizedBy p.publish
    p.afterReleaseBuild.dependsOn p.pushDocker
    p.afterReleaseBuild.finalizedBy p.prepareDeploy
  }
}
