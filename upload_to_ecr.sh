# build the Docker image
docker build --no-cache -t agentcore-runtime-koog-agent-demo:v1 .

# Login to ECR
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin YOUR_ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com

# Create ECR repository
aws ecr create-repository --repository-name agentcore-runtime-koog-agent-demo --image-scanning-configuration scanOnPush=true --region us-east-1

# Tag the Docker image
docker tag agentcore-runtime-koog-agent-demo:v1 YOUR_ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/agentcore-runtime-koog-agent-demo:v1

# Push the Docker Image to the ECR repository
docker push YOUR_ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/agentcore-runtime-koog-agent-demo:v1
