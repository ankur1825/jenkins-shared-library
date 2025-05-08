# Start from the official Jenkins LTS image
FROM jenkins/jenkins:lts

USER root

# Install dependencies
RUN apt-get update && apt-get install -y \
    zip \
    curl \
    gnupg2 \
    lsb-release \
    apt-transport-https \
    software-properties-common \
    unzip \
    git \
    ca-certificates \
    sudo \
    docker.io \
    libc6 \
    libstdc++6 \
    libncurses5 \
    libzip4 \
    liblzma5 \
    libgomp1 \
    libc6-dev \
    zlib1g \
    libnss3 \
    libtinfo6 \
    libxrender1 \
    libxtst6 \
    libxi6 \
    && rm -rf /var/lib/apt/lists/*

# Assuming Dockerfile is at root and plugins/ is in same directory
#COPY plugins/apache-httpcomponents-client-4-api.jpi /usr/share/jenkins/ref/plugins/

RUN jenkins-plugin-cli --plugins \
    configuration-as-code \
    ldap \
    role-strategy

# Create a new admin user via groovy script
COPY security.groovy /usr/share/jenkins/ref/init.groovy.d/security.groovy

# Preinstall plugins
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# Install OpenJDK 11 from Eclipse Temurin
RUN mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y temurin-11-jdk

# Install Maven
COPY binaries/apache-maven-3.9.9-bin.tar.gz /tmp/
RUN tar -xzf /tmp/apache-maven-3.9.9-bin.tar.gz -C /opt
#RUN tar -xzf /tmp/apache-maven-3.9.9-bin.tar.gz -C /opt
ENV MVN_HOME=/opt/apache-maven-3.9.9
ENV PATH=$PATH:$MVN_HOME/bin

#Install Gradle
COPY binaries/gradle.zip /tmp/
RUN unzip -o /tmp/gradle.zip -d /opt/gradle
ENV GRADLE_HOME=/opt/gradle/gradle-*
ENV PATH=$PATH:$GRADLE_HOME/bin

# Install Docker CLI (client only)
RUN curl -fsSL https://download.docker.com/linux/static/stable/x86_64/docker-27.4.0.tgz | tar -xz -C /usr/bin --strip-components=1 docker/docker

# Install Helm
COPY binaries/linux-amd64/helm /usr/bin/helm
RUN chmod +x /usr/bin/helm

# Install kubectl
COPY binaries/kubectl /usr/local/bin/kubectl
RUN chmod +x /usr/local/bin/kubectl


# Install SonarScanner
ENV SONAR_SCANNER_VERSION=5.0.1.3006

COPY binaries/sonar-scanner-cli-${SONAR_SCANNER_VERSION}-linux.zip /tmp/
RUN unzip -o /tmp/sonar-scanner-cli-${SONAR_SCANNER_VERSION}-linux.zip -d /opt && \
    mv /opt/sonar-scanner-${SONAR_SCANNER_VERSION}-linux /opt/sonar-scanner && \
    ln -s /opt/sonar-scanner/bin/sonar-scanner /usr/local/bin/sonar-scanner

ENV SONAR_SCANNER_HOME=/opt/sonar-scanner
ENV PATH="${SONAR_SCANNER_HOME}/bin:${PATH}"

# Set permissions
RUN chown -R jenkins:jenkins /usr/share/jenkins/ref

USER jenkins

