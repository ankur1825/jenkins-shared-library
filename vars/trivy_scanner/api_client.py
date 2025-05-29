# api_client.py
import requests
from models import Vulnerability
from config import BACKEND_API_URL

def upload_vulnerabilities(vulnerabilities, application_name: str, jenkins_job: str, build_number: int):
    payload = {
        "application": application_name,
        "vulnerabilities": [v.__dict__ | {
            "source": "Trivy",
            "jenkins_job": jenkins_job,
            "build_number": build_number
        } for v in vulnerabilities]
    }

    response = requests.post(BACKEND_API_URL, json=payload)

    if response.status_code == 200:
        print("Successfully uploaded vulnerabilities!")
    else:
        print(f"Failed to upload vulnerabilities: {response.status_code}, {response.text}")

