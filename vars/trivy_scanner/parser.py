# parser.py
import json
from models import Vulnerability

def parse_trivy_output(filepath: str):
    with open(filepath, "r") as f:
        data = json.load(f)

    vulnerabilities = []

    for result in data.get("Results", []):
        for vuln in result.get("Vulnerabilities", []):
            vulnerabilities.append(Vulnerability(
                target=result.get("Target"),
                package_name=vuln.get("PkgName"),
                installed_version=vuln.get("InstalledVersion"),
                vulnerability_id=vuln.get("VulnerabilityID"),
                severity=vuln.get("Severity"),
                description=vuln.get("Description"),
                fixed_version=vuln.get("FixedVersion"),
                cvss_score=vuln.get("CVSS", [{}])[0].get("V3Score", 0.0),  # Trivy provides CVSS sometimes
            ))

    return vulnerabilities
