def call(Map params = [:]) {
    def enforcementEnabled = (
        (env.ENTERPRISE_LICENSE_ENFORCEMENT_ENABLED ?: '').toString().equalsIgnoreCase('true') ||
        (params.LICENSE_VALIDATION_MODE ?: '').toString().equalsIgnoreCase('enforced')
    )

    if (!enforcementEnabled) {
        echo "Enterprise license enforcement is disabled for this run."
        return
    }

    def clientId = str(params.CLIENT_ID)
    def expiresAt = str(params.LICENSE_EXPIRES_AT)
    def pipelineName = str(params.PIPELINE_NAME ?: params.SERVICE_NAME ?: 'Devops Pipeline')
    def targetEnv = str(params.TARGET_ENV ?: 'EKS-NONPROD')

    if (!clientId) {
        error "License validation failed: CLIENT_ID is required."
    }
    if (!expiresAt) {
        error "License validation failed: LICENSE_EXPIRES_AT is required."
    }
    if (isExpired(expiresAt)) {
        error "License validation failed: license expired at ${expiresAt}."
    }

    def licensedPipelines = csv(params.LICENSED_PIPELINES)
    if (licensedPipelines && !containsIgnoreCase(licensedPipelines, pipelineName)) {
        error "License validation failed: pipeline '${pipelineName}' is not enabled."
    }

    def licensedEnvironments = csv(params.LICENSED_ENVIRONMENTS)
    if (licensedEnvironments && !containsIgnoreCase(licensedEnvironments, targetEnv)) {
        error "License validation failed: environment '${targetEnv}' is not enabled."
    }

    def licensedFeatures = csv(params.LICENSED_FEATURES)
    def requestedFeatures = requestedFeatures(params)
    requestedFeatures.each { feature ->
        if (licensedFeatures && !containsIgnoreCase(licensedFeatures, feature)) {
            error "License validation failed: feature '${feature}' is not enabled."
        }
    }

    echo "License validation passed for client '${clientId}', pipeline '${pipelineName}', environment '${targetEnv}'."
}

def requestedFeatures(Map params) {
    def features = ['build', 'artifact_publish']
    if (asBool(params.ENABLE_SONARQUBE)) {
        features << 'code_scan'
    }
    if (asBool(params.ENABLE_TRIVY)) {
        features << 'image_scan'
    }
    if (asBool(params.ENABLE_CHECKMARX)) {
        features << 'static_application_security'
    }
    if (asBool(params.ENABLE_SOAPUI) || asBool(params.ENABLE_JMETER) || asBool(params.ENABLE_SELENIUM) || asBool(params.ENABLE_NEWMAN)) {
        features << 'test_suites'
    }
    if (str(params.TARGET_ENV).toUpperCase().contains('PROD')) {
        features << 'prod_deploy'
    }
    if (asBool(params.ENABLE_NOTIFICATIONS)) {
        features << 'notifications'
    }
    return features.unique()
}

def isExpired(String expiresAt) {
    try {
        def normalized = expiresAt.endsWith('Z') ? expiresAt.replace('Z', '+00:00') : expiresAt
        def expiry = java.time.OffsetDateTime.parse(normalized)
        return !expiry.isAfter(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC))
    } catch (Exception e) {
        error "License validation failed: invalid LICENSE_EXPIRES_AT '${expiresAt}'. Expected ISO-8601 timestamp."
    }
}

def containsIgnoreCase(List values, String expected) {
    values.any { it.toString().trim().equalsIgnoreCase(expected.toString().trim()) }
}

def csv(Object value) {
    str(value).split(',').collect { it.trim() }.findAll { it }
}

def asBool(Object value) {
    value instanceof Boolean ? value : str(value).equalsIgnoreCase('true')
}

def str(Object value) {
    (value ?: '').toString().trim()
}
