import json
import sys
import os
from datetime import datetime

input_file = sys.argv[1]
output_file = sys.argv[2]

if not os.path.exists(input_file) or os.path.getsize(input_file) == 0:
    print("[INFO] No issues found or file is empty. Creating empty output.")
    with open(output_file, "w") as f:
        json.dump([], f, indent=2)
    sys.exit(0)

try:
    with open(input_file) as f:
        data = json.load(f)
except json.JSONDecodeError:
    print("[ERROR] Failed to parse JSON. Input file is invalid.")
    sys.exit(1)

# Grab Jenkins metadata from environment
jenkins_job = os.getenv("JOB_NAME", "unknown_job")
build_number = os.getenv("BUILD_NUMBER", "0")
jenkins_base = os.getenv("JENKINS_URL", "https://horizonrelevance.com/jenkins")
jenkins_url = f"{jenkins_base}/job/{jenkins_job}/{build_number}/console"

sast_keywords = {
    "sql injection": "CRITICAL",
    "command injection": "CRITICAL",
    "insecure deserialization": "CRITICAL",
    "deserialization": "CRITICAL",
    "hardcoded password": "HIGH",
    "hard-coded credential": "HIGH",
    "credential": "HIGH",
    "secret": "HIGH",
    "private key": "HIGH",
    "xss": "HIGH",
    "cross-site scripting": "HIGH",
    "path traversal": "HIGH",
    "xxe": "HIGH",
    "server-side request forgery": "HIGH",
    "ssrf": "HIGH",
    "sensitive data exposure": "HIGH",
    "weak cryptography": "HIGH",
    "weak encryption": "HIGH",
    "csrf": "MEDIUM",
    "open redirect": "MEDIUM",
    "regex denial of service": "MEDIUM",
    "redos": "MEDIUM",
    "buffer overflow": "CRITICAL"
}

blocking_severities = {
    item.strip().upper()
    for item in os.getenv("SECURITY_FAIL_ON_SEVERITY", "BLOCKER,CRITICAL,HIGH").split(",")
    if item.strip()
}

risk_score_map = {
    "LOW": 20,
    "MEDIUM": 50,
    "HIGH": 80,
    "CRITICAL": 95
}

severity_order = {
    "LOW": 1,
    "MEDIUM": 2,
    "HIGH": 3,
    "CRITICAL": 4
}


def normalize_sonar_severity(value: str) -> str:
    severity = (value or "").upper()
    return {
        "BLOCKER": "CRITICAL",
        "CRITICAL": "CRITICAL",
        "MAJOR": "HIGH",
        "MINOR": "MEDIUM",
        "INFO": "LOW",
        "HIGH": "HIGH",
        "MEDIUM": "MEDIUM",
        "LOW": "LOW",
    }.get(severity, "LOW")


def stronger_severity(left: str, right: str) -> str:
    return left if severity_order.get(left, 0) >= severity_order.get(right, 0) else right


processed = []
fail_pipeline = False

for issue in data.get("issues", []):
    message = issue.get("message", "").lower()
    sonar_severity = normalize_sonar_severity(issue.get("severity", ""))
    predicted_severity = "LOW"

    for keyword, severity in sast_keywords.items():
        if keyword in message:
            predicted_severity = severity
            break

    final_severity = stronger_severity(sonar_severity, predicted_severity)

    if issue.get("severity", "").upper() in blocking_severities or final_severity in blocking_severities:
        fail_pipeline = True

    risk_score = risk_score_map.get(final_severity, 20)
    rule = issue.get("rule", "")
    remediation = f"Review rule {rule} and remediate: {issue.get('message', '')}".strip()

    processed.append({
        "target": issue.get("component", ""),
        "package_name": "Code Analysis",
        "installed_version": "N/A",
        "vulnerability_id": rule,
        "description": issue.get("message", ""),
        "line": issue.get("line", 0),
        "type": issue.get("type", ""),
        "severity": final_severity,
        "predictedSeverity": predicted_severity,
        "sonarSeverity": issue.get("severity", ""),
        "fixed_version": remediation,
        "risk_score": risk_score,
        "rule": rule,
        "status": issue.get("status", ""),
        "author": issue.get("author", ""),
        "source": "SonarQube",
        "timestamp": datetime.utcnow().isoformat() + "Z",
        "jenkins_job": jenkins_job,
        "build_number": int(build_number),
        "jenkins_url": jenkins_url
    })

with open(output_file, "w") as f:
    json.dump(processed, f, indent=2)

if fail_pipeline:
    print("FAIL_PIPELINE=true")
else:
    print("FAIL_PIPELINE=false")
