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
    applyRepositories project
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
    }
  }

  private static void applyRepositories(Project p) {
    p.repositories {
      mavenCentral()
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
          if (p.version.endsWith('-SNAPSHOT')) {
            url p.property('AWS_MAVEN_SNAPSHOTS')
          } else {
            url p.property('AWS_MAVEN_RELEASES')
          }
          credentials(AwsCredentials) {
            accessKey p.property('AWS_ACCESS_KEY')
            secretKey p.property('AWS_SECRET_KEY')
          }
        }
      }

      publications {
        mavenJava(MavenPublication) {
          version = p.version
          artifactId = p.name
          groupId = p.group
          artifact("${p.buildDir}/libs/${p.name}-${p.version}.jar")
        }
      }
    }
  }

  private static void createDockerTasks(Project p) {

    p.tasks.create("buildDocker", DockerTask.class) {
      baseImage 'java:8'
      maintainer 'Rowell Belen "developer@bytekast.com"'
      push = false
      applicationName = "${p.name}"
      runCommand "apt-get update"
      workingDir '/app'
      addFile "${p.buildDir}/libs/${p.name}-${p.version}.jar", "/app/${p.name}-${p.version}.jar"
      exposePort 8080
      defaultCommand(['java', '-jar', "${p.name}-${p.version}.jar"])
    }

    p.tasks.create('pushDocker') {
      doLast {
        def localTag = "${p.group}/${p.name}:${p.version}"
        def deployTag = "${p.property('AWS_DOCKER_REPO')}/${p.name}:${p.version}"
        p.exec {
          commandLine('docker', 'tag', localTag, deployTag)
        }
        p.exec {
          commandLine('docker', 'push', deployTag)
        }
      }
    }

  }

  private static void createDeployTasks(Project p) {
    p.tasks.create('prepareDeploy') {
      doLast {
        def deployTag = "${p.property('AWS_DOCKER_REPO')}/${p.name}:${p.version}"
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
        def file = File.newInstance(p.projectDir, 'Dockerrun.aws.json')
        file.text = groovy.json.JsonBuilder.newInstance(json).toPrettyString()
      }
    }
  }

  private static void createTasksDependencies(Project p) {
    p.pushDocker.dependsOn p.buildDocker
    p.pushDocker.finalizedBy p.publish
    p.afterReleaseBuild.dependsOn p.pushDocker
    p.afterReleaseBuild.finalizedBy p.prepareDeploy
  }
}
