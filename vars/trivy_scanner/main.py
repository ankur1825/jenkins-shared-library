# # main.py
# import argparse
# import json
# import os
# import requests
# from scanner import scan_image

# def parse_args():
#     parser = argparse.ArgumentParser(description="Trivy vulnerability scanner and uploader")
#     parser.add_argument("--image", required=True, help="Docker image to scan")
#     parser.add_argument("--application", required=True, help="Application name")
#     parser.add_argument("--jenkins-job", required=True, help="Jenkins job name")
#     parser.add_argument("--build-number", type=int, required=True, help="Jenkins build number")
#     parser.add_argument("--repo-url", required=True, help="Repository URL")
#     parser.add_argument("--jenkins-url", required=True, help="Base Jenkins URL")
#     parser.add_argument("--requested-by", required=True, help="User who triggered the Jenkins job")
#     parser.add_argument("--upload-url", default="https://horizonrelevance.com/pipeline/api/upload_vulnerabilities", help="Backend API to upload results")
#     return parser.parse_args()

# def main():
#     args = parse_args()

#     print(f"[INFO] Scanning image: {args.image}")
#     trivy_vulns = scan_image(args.image)

#     if not trivy_vulns:
#         print("[INFO] No vulnerabilities found.")
#         return

#     full_jenkins_url = f"{args.jenkins_url}/job/{args.jenkins_job}/{args.build_number}/"

#     payload = {
#         "application": args.application,
#         "requestedBy": args.requested_by,
#         "repo_url": args.repo_url,
#         "jenkins_url": full_jenkins_url,
#         "jenkins_job": args.jenkins_job,
#         "build_number": args.build_number,
#         "vulnerabilities": []
#     }

#     for vuln in trivy_vulns:
#         vuln["application"] = args.application
#         vuln["jenkins_job"] = args.jenkins_job
#         vuln["build_number"] = args.build_number
#         vuln["jenkins_url"] = full_jenkins_url
#         payload["vulnerabilities"].append(vuln)

#     headers = {"Content-Type": "application/json"}
#     try:
#         print(f"[INFO] Uploading {len(trivy_vulns)} vulnerabilities to {args.upload_url}")
#         response = requests.post(args.upload_url, data=json.dumps(payload), headers=headers, verify=False)
#         print(f"[Upload API] Status: {response.status_code}, Body: {response.text}")
#     except Exception as e:
#         print(f"[ERROR] Failed to upload vulnerabilities: {e}")

# if __name__ == "__main__":
#     main()

import click
import sys
from scanner import scan_image
from trivy_parser import parse_trivy_output
from api_client import upload_vulnerabilities

@click.command()
@click.option('--image', prompt='Docker image name', help='The docker image to scan.')
@click.option('--upload', is_flag=True, help='Upload results to backend API.')
@click.option('--application', required=True, help='Application name to associate the scan with.')
@click.option('--jenkins-job', required=True, help='Jenkins job name.')
@click.option('--build-number', required=True, type=int, help='Jenkins build number.')
@click.option('--repo-url', required=True, help='GitHub repo URL associated with this image.')
@click.option('--jenkins-url', required=True, help='Jenkins build URL.')
@click.option('--requested-by', required=True, help='User who triggered the Jenkins job.')
def main(image, upload, application, jenkins_job, build_number, repo_url, jenkins_url, requested_by):
    print(f"Scanning Docker image: {image}")
    scan_image(image)

    vulnerabilities = parse_trivy_output("trivy-report.json")
    print(f"✅ Scan complete. Found {len(vulnerabilities)} vulnerabilities.\n")

    blocking = [v for v in vulnerabilities if v.severity.upper() in {"CRITICAL", "HIGH"}]

    for v in vulnerabilities:
        print(f"- {v.severity}: {v.package_name} ({v.vulnerability_id})")
        print(f"     Fix: {v.fixed_version}")
        #print(f"     Risk Score: {v.get('risk_score', 'N/A')}")
        #print(f"     Description: {v.get('title')[:80]}...\n")

    if upload:
        print("Uploading results to backend...")
        upload_vulnerabilities(
            vulnerabilities,
            application_name=application,
            jenkins_job=jenkins_job,
            build_number=build_number,
            repo_url=repo_url,
            jenkins_url=jenkins_url,
            requested_by=requested_by
        )
        print("Upload complete.")

    # Fail pipeline on CRITICAL or HIGH
    if blocking:
        print(f"Build failed due to {len(blocking)} (CRITICAL or HIGH vulnerabilities).")
        sys.exit(1)    

    print("No blocking vulnerabilities. Pipeline can proceed.")    

if __name__ == "__main__":
    main()
