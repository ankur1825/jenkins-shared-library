import json
import sys

input_file = sys.argv[1]
output_file = sys.argv[2]

# Define SAST vulnerability patterns and mapped severity
sast_keywords = {
    "sql injection": "Critical",
    "xss": "High",
    "cross-site scripting": "High",
    "hardcoded password": "High",
    "command injection": "Critical",
    "insecure deserialization": "Critical",
    "csrf": "Medium",
    "cross-site request forgery": "Medium",
    "open redirect": "Medium",
    "path traversal": "High",
    "directory traversal": "High",
    "buffer overflow": "Critical",
    "cryptographic weakness": "Medium",
    "weak encryption": "Medium",
    "ldap injection": "High",
    "sensitive data exposure": "High"
}

# Risk scores mapping
risk_score_map = {
    "Low": 1,
    "Medium": 2,
    "High": 3,
    "Critical": 4
}

with open(input_file) as f:
    data = json.load(f)

processed = []
for issue in data.get("issues", []):
    message = issue.get("message", "").lower()
    prediction = "Low"

    # Match against known SAST patterns
    for keyword, severity in sast_keywords.items():
        if keyword in message:
            prediction = severity
            break

    risk_score = risk_score_map[prediction]

    processed.append({
        "target": issue.get("component", ""),
        "message": issue.get("message", ""),
        "line": issue.get("line", 0),
        "type": issue.get("type", ""),
        "severity": issue.get("severity", ""),
        "predictedSeverity": prediction,
        "riskScore": risk_score,
        "rule": issue.get("rule", ""),
        "status": issue.get("status", ""),
        "author": issue.get("author", ""),
        "source": "SonarQube"
    })

with open(output_file, "w") as f:
    json.dump(processed, f, indent=2)
