pipeline {
    agent {
        label 'windows-docker'
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
    }

    tools {
        jdk 'JDK21'
    }

    stages {
        stage('Test Backend') {
            steps {
                dir('backend') {
                    bat 'mvnw.cmd test'
                }
            }
        }
    }
    post {
        always {
            junit 'backend/target/surefire-reports/*.xml'
        }
    }
}