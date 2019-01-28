node {
    try { 
        stage('Clone') {
            checkout scm
        }
        
        withMaven(maven: 'M3', jdk: 'jdk-oracle-8', options: [artifactsPublisher(disabled: true), junitPublisher(disabled: false)] ) {
            stage('Build') {
                sh "mvn clean compile -DskipTests"
            }
            
            stage('Test') {
                sh "mvn verify -Prun-its"
            }
        
            stage('Deploy') {
                if (env.BRANCH_NAME == "master" || env.BRANCH_NAME == "jenkins-deploy") {
                    sh "mvn deploy"
                }
            }
        }
        
        if (currentBuild.previousBuild.result == "FAILURE") { 
            slackSend (color: '#5cb85c', channel: "#rascal", message: "BUILD BACK TO NORMAL:  <${env.BUILD_URL}|${env.JOB_NAME} [${env.BUILD_NUMBER}]>")
        }
    } catch(e) {
        slackSend (color: '#d9534f', channel: "#rascal", message: "FAILED: <${env.BUILD_URL}|${env.JOB_NAME} [${env.BUILD_NUMBER}]>")
        throw e
    }
}
