# models.py
from dataclasses import dataclass
@dataclass
class Vulnerability:
    target: str
    package_name: str
    installed_version: str
    vulnerability_id: str
    severity: str
    title: str
    description: str
    fixed_version: str
    cvss_score: float = 0.0
    exploitability_score: float = 0.0
    application: str = ""
    jenkins_job: str = ""
    build_number: int = 0

    def calculate_risk_score(self):
        severity_map = {
            "CRITICAL": 10,
            "HIGH": 7,
            "MEDIUM": 5,
            "LOW": 3,
            "UNKNOWN": 1
        }
        severity_key = (self.severity or "UNKNOWN").upper()
        severity_score = severity_map.get(severity_key, 1)
        
        fix_available_score = 1.0 if self.fixed_version and self.fixed_version.upper() != "N/A" else 0.0

        risk_score = (severity_score * 0.4) + (self.cvss_score * 0.3) + (self.exploitability_score * 0.2) + (fix_available_score * 0.1)
        return round(risk_score, 2)

