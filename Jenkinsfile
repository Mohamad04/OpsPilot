pipeline {
    agent {
            label 'windows-docker'
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
}