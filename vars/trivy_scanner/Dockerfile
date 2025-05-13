# Use official lightweight Python image
FROM python:3.11-slim

# Install Trivy inside container
RUN apt-get update && \
    apt-get install -y wget rpm && \
    wget https://github.com/aquasecurity/trivy/releases/latest/download/trivy_0.62.1_Linux-64bit.deb && \
    dpkg -i trivy_0.62.1_Linux-64bit.deb && \
    rm trivy_0.62.1_Linux-64bit.deb

# Set working directory
WORKDIR /app

# Copy all files into container
COPY . .

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Set entrypoint
ENTRYPOINT ["python", "main.py"]
