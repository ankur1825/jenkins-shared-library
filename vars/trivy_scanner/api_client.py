# api_client.py
import requests
from models import Vulnerability
from config import BACKEND_API_URL

def upload_vulnerabilities(vulnerabilities):
    payload = [v.__dict__ for v in vulnerabilities]
    response = requests.post(BACKEND_API_URL, json={"vulnerabilities": payload})

    if response.status_code == 200:
        print("✅ Successfully uploaded vulnerabilities!")
    else:
        print(f"❌ Failed to upload vulnerabilities: {response.status_code}, {response.text}")
