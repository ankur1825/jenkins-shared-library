def call(String appName, String tag) {
    def input = [
        "image_name": "${appName}:${tag}",
        "root_user": true,        // example static value
        "secrets_detected": false,
        "ports": [80, 443, 22]
    ]
    return groovy.json.JsonOutput.toJson([input: input])
}
