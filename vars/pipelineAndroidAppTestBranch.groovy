#!/usr/bin/env groovy

def call(Closure body={}) {
    // evaluate the body block, and collect configuration into the object
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {
        agent none

        options {
            timeout(time: 1, unit: 'HOURS')
            skipDefaultCheckout()
            ansiColor('xterm')
            retry(3)
        }

        triggers {
            pollSCM('H * * * *')
        }

        environment {
            LANG = "C.UTF-8"
            LC_ALL = "en_US.UTF-8"
            LANGUAGE = "en_US.UTF-8"
            UNITTESTING_STATE = 'false'
            TESTING_STATE = 'false'
        }

        stages {
            stage('Check Branch/Tag') {
                agent {
                    node {
                        label 'master'
                        customWorkspace "workspace/test_dev"
                    }
                }
                when {
                    beforeAgent true
                    not {
                        anyOf {
                            branch "test/*"
                        }
                    }
                }
                steps {
                    error "Don't know what to do with this branch or tag: ${env.BRANCH_NAME}"
                }
            }

            stage('Checkout SCM') {
                agent {
                    node {
                        label 'mac-mini1'
                        customWorkspace "workspace/test_dev"
                    }
                }
                when {
                    beforeAgent true
                    branch "test/*"
                }
                steps {
                    script {
                        def scmVars = checkoutGitlab()
                    }
                }
            }

            stage('Unit Testing') {
                agent {
                    node {
                        label 'mac-mini1'
                        customWorkspace "workspace/test_dev"
                    }
                }
                environment {
                    ANDROID_SDK_ROOT = "${HOME}/Library/Android/sdk"
                    ANDROID_HOME = "${ANDROID_SDK_ROOT}"
                }
                when {
                    beforeAgent true
                    environment name: 'UNITTESTING_STATE', value: 'true'
                }
                steps {
                    // unittestTestBranch(buildTypes, productFlavors)
                    echo 'pass'
                }
            }

            stage("Incerease version code") {
                agent {
                    node {
                        label 'mac-mini1'
                        customWorkspace "workspace/test_dev"
                    }
                }
                environment {
                    ANDROID_SDK_ROOT = "${HOME}/Library/Android/sdk"
                    //ANDROID_SDK_ROOT = "/usr/local/Caskroom/android-sdk/4333796"
                    ANDROID_HOME = "${ANDROID_SDK_ROOT}"
                    PATH = "/Users/mac/.rbenv/shims:/usr/local/bin:${PATH}"
                }
                steps {
                    sh '''
                    curl 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=59122387-2ec3-4cad-932e-0979efa71f89' \
                       -H 'Content-Type: application/json' \
                       -d '
                           {
                                "msgtype": "text",
                                "text": {
                                    "content": "Build start..."
                                }
                           }'
                    '''
                    sh '''
                    export version_code=$(awk '/versionCode/ {print $NF}' config.gradle | cut -d ',' -f 1); sed  -i'' -e "s/versionCode      : ${version_code}/versionCode      : $[${version_code}+1]/g" config.gradle
                    '''
                }
            }

            stage('Build China') {
                agent {
                    node {
                        label 'mac-mini1'
                        customWorkspace "workspace/test_dev"
                    }
                }
                environment {
                    ANDROID_SDK_ROOT = "${HOME}/Library/Android/sdk"
                    //ANDROID_SDK_ROOT = "/usr/local/Caskroom/android-sdk/4333796"
                    ANDROID_HOME = "${ANDROID_SDK_ROOT}"
                    PATH = "/Users/mac/.rbenv/shims:/usr/local/bin:${PATH}"
                }
                steps {

                    sh '''
                    ./gradlew -v
                    ./gradlew clean 
                    ./gradlew assembleChinaRelease
                    '''
                    sh '''
                    curl 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=59122387-2ec3-4cad-932e-0979efa71f89' \
                       -H 'Content-Type: application/json' \
                       -d '
                           {
                                "msgtype": "text",
                                "text": {
                                    "content": "Gradle task for China success."
                                }
                           }'
                    '''
                }
            }

            stage('Build Google') {
                agent {
                    node {
                        label 'mac-mini1'
                        customWorkspace "workspace/test_dev"
                    }
                }
                environment {
                    ANDROID_SDK_ROOT = "${HOME}/Library/Android/sdk"
                    //ANDROID_SDK_ROOT = "/usr/local/Caskroom/android-sdk/4333796"
                    ANDROID_HOME = "${ANDROID_SDK_ROOT}"
                    PATH = "/Users/mac/.rbenv/shims:/usr/local/bin:${PATH}"
                }
                steps {
                    sh '''
                    ./gradlew assembleGoogleRelease
                    '''
                    sh '''
                    curl 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=59122387-2ec3-4cad-932e-0979efa71f89' \
                       -H 'Content-Type: application/json' \
                       -d '
                           {
                                "msgtype": "text",
                                "text": {
                                    "content": "Gradle task for Google success."
                                }
                           }'
                    '''
                }
            }

            stage('UPload') {
                agent {
                    node {
                        label 'mac-mini1'
                        customWorkspace "workspace/test_dev"
                    }
                }
                environment {
                    ANDROID_SDK_ROOT = "${HOME}/Library/Android/sdk"
                    //ANDROID_SDK_ROOT = "/usr/local/Caskroom/android-sdk/4333796"
                    ANDROID_HOME = "${ANDROID_SDK_ROOT}"
                    PATH = "/Users/mac/.rbenv/shims:/usr/local/bin:${PATH}"
                }
                steps {
                    buildTestBranch()
                }
            }

            stage("Git commit") {
                when {
                    expression {
                        currentBuild.result == null || currentBuild.result == 'SUCCESS' 
                    }
                }
                agent {
                    node {
                        label 'mac-mini1'
                        customWorkspace "workspace/test_dev"
                    }
                }
                steps {
                    sh '''
                    git add config.gradle
                    git commit -m "Increase versionCode automatically."
                    if ! [[ $(git remote | grep Bitbucket) ]]
                    then
                        git remote add Bitbucket ssh://git@git.hellotalk8.com:7999/android/ht_android.git
                    fi
                    git pull Bitbucket $(git branch | awk '{print $2}')
                    git push --set-upstream Bitbucket $(git branch | awk '{print $2}')
                    '''
                }
            }

        post {
            failure {
                sh '''
                    curl 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=59122387-2ec3-4cad-932e-0979efa71f89' \
                       -H 'Content-Type: application/json' \
                       -d '
                           {
                                "msgtype": "text",
                                "text": {
                                    "content": "Jenkins task failed..."
                                }
                           }'
                    '''
            }
        }
    }
}

def unittestTestBranch(String buildTypes='', String productFlavors='') {
    echo "Test branch - Unit Testing"
    buildTypes = pipelineAndroidAppSetup.changeStringGradleStyle(buildTypes)
    productFlavors = pipelineAndroidAppSetup.changeStringGradleStyle(productFlavors)
    def args = ((productFlavors ?: '') + (buildTypes ?: '')) ? (((productFlavors ?: '') + (buildTypes ?: '')) + 'UnitTest' ) : ''
    pipelineAndroidAppSetup.unittest(args)
}

def buildTestBranch() {
    echo "Test branch - Build"
    // buildTypes = pipelineAndroidAppSetup.changeStringGradleStyle(buildTypes)
    // productFlavors = pipelineAndroidAppSetup.changeStringGradleStyle(productFlavors)
    // def args = ((productFlavors ?: '') + (buildTypes ?: '')) //+ " publish"
    // pipelineAndroidAppSetup.build(args)
    sh 'bundle install'
    // sh 'bundle update'
    sh 'bundle exec fastlane android do_publish_all'
}

def wechatAll() {
    sh 'bundle exec fastlane android do_wechat_all'
}

def artifactsTestBranch(String buildTypes = '', String productFlavors = '') {
    echo "Test branch - Artifacts"
    def name = "${App}" + (((productFlavors ? ('-' + productFlavors) : '') + (buildTypes ? ('-'+ buildTypes) : '')) ?: '')
    def path = "${App}/build/outputs/apk/" + (productFlavors ?: '*') + '/' + (buildTypes ?: '*') + "/${App}-" + (productFlavors ?: '*') + '-' + (buildTypes ?: '*') + '.apk'
    pipelineAndroidAppSetup.artifacts(name, path)
}

def deployTestBranch(String buildTypes = '', String productFlavors = '') {
    echo "Test branch - Deploy"
    def name = "${App}" + (((productFlavors ? ('-' + productFlavors) : '') + (buildTypes ? ('-'+ buildTypes) : '')) ?: '')
    def path = "${App}/build/outputs/apk/" + (productFlavors ?: '*') + '/' + (buildTypes ?: '*') + "/${App}-" + (productFlavors ?: '*') + '-' + (buildTypes ?: '*') + '.apk'
    def targetPath = "/var/www/nginx/html/testing.hellotalk.com/android/package/"
    pipelineAndroidAppSetup.deploy(name, path, targetPath)
}
