# api_client.py
import requests
import os
from models import Vulnerability
from config import BACKEND_API_URL

def upload_vulnerabilities(vulnerabilities, application_name: str, repo_url: str, jenkins_url: str, jenkins_job: str, build_number: int, requested_by: str):
    payload = {
        "application": application_name,
        "requestedBy": requested_by,
        "repo_url": repo_url,
        "jenkins_url": jenkins_url,
        "jenkins_job": jenkins_job,
        "build_number": build_number,
        "vulnerabilities": [v.__dict__ | {
            "source": "Trivy",
            "jenkins_job": jenkins_job,
            "build_number": build_number
        } for v in vulnerabilities]
    }

    response = requests.post(BACKEND_API_URL, json=payload)
    print(f"[Upload API] Status: {response.status_code}, Body: {response.text}")

    if response.status_code == 200:
        print("Successfully uploaded vulnerabilities!")
    else:
        print(f"Failed to upload vulnerabilities: {response.status_code}, {response.text}")

