import requests
from models import Vulnerability
from config import BACKEND_API_URL


def _finding_payload(vulnerability: Vulnerability) -> dict:
    return {
        "target": vulnerability.target,
        "package_name": vulnerability.package_name,
        "installed_version": vulnerability.installed_version,
        "vulnerability_id": vulnerability.vulnerability_id,
        "severity": vulnerability.severity,
        "title": vulnerability.title,
        "description": vulnerability.description,
        "fixed_version": vulnerability.fixed_version,
        "risk_score": vulnerability.calculate_risk_score(),
        "source": vulnerability.source,
        "line": vulnerability.line,
        "rule": vulnerability.rule or vulnerability.vulnerability_id,
        "status": vulnerability.status,
        "jenkins_job": vulnerability.jenkins_job,
        "build_number": vulnerability.build_number,
    }


def upload_vulnerabilities(vulnerabilities, application_name: str, repo_url: str, jenkins_url: str, jenkins_job: str, build_number: int, requested_by: str):
    payload = {
        "application": application_name,
        "requestedBy": requested_by,
        "repo_url": repo_url,
        "jenkins_url": jenkins_url,
        "jenkins_job": jenkins_job,
        "build_number": build_number,
        "vulnerabilities": [_finding_payload(v) for v in vulnerabilities]
    }

    response = requests.post(BACKEND_API_URL, json=payload, timeout=30)
    print(f"[Upload API] Status: {response.status_code}, Body: {response.text}")
    response.raise_for_status()

    print("Successfully uploaded security findings.")
