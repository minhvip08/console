#!/bin/bash
# LocalStack init script — runs automatically when LocalStack is ready.
# Provisions AWS resources that match docs/10-aws-infrastructure.md.

set -euo pipefail

REGION=ap-northeast-2
ENDPOINT=http://localhost:4566

echo "════════════════════════════════════════════════════════════"
echo "  LocalStack Init: Provisioning AWS resources for Console"
echo "════════════════════════════════════════════════════════════"

# ─── S3 Buckets (docs/10 §S3) ───
echo "→ Creating S3 buckets..."
awslocal s3 mb s3://console-artifacts --region $REGION 2>/dev/null || true
awslocal s3 mb s3://console-archive --region $REGION 2>/dev/null || true
awslocal s3 mb s3://console-static --region $REGION 2>/dev/null || true

# S3 lifecycle policy (docs/10 §Lifecycle Policy)
awslocal s3api put-bucket-lifecycle-configuration \
  --bucket console-artifacts \
  --lifecycle-configuration '{
    "Rules": [
      {
        "ID": "archive-old-executions",
        "Prefix": "executions/",
        "Status": "Enabled",
        "Transitions": [
          { "Days": 30, "StorageClass": "STANDARD_IA" },
          { "Days": 365, "StorageClass": "GLACIER" }
        ],
        "Expiration": { "Days": 1095 }
      }
    ]
  }' 2>/dev/null || true

echo "  ✓ S3 buckets: console-artifacts, console-archive, console-static"

# ─── CloudWatch Log Groups (docs/10 §CloudWatch) ───
echo "→ Creating CloudWatch Log Groups..."
awslocal logs create-log-group --log-group-name /ecs/console-api --region $REGION 2>/dev/null || true
awslocal logs create-log-group --log-group-name /ecs/backoffice-api --region $REGION 2>/dev/null || true
awslocal logs create-log-group --log-group-name /ecs/reconciliation-service --region $REGION 2>/dev/null || true

# Set retention (7 days for dev, longer in prod)
awslocal logs put-retention-policy --log-group-name /ecs/console-api --retention-in-days 7 --region $REGION 2>/dev/null || true
awslocal logs put-retention-policy --log-group-name /ecs/backoffice-api --retention-in-days 7 --region $REGION 2>/dev/null || true
awslocal logs put-retention-policy --log-group-name /ecs/reconciliation-service --retention-in-days 7 --region $REGION 2>/dev/null || true

echo "  ✓ CloudWatch Log Groups created"

# ─── CloudWatch Alarms (docs/10 §CloudWatch metrics) ───
echo "→ Creating CloudWatch Alarms..."

awslocal cloudwatch put-metric-alarm \
  --alarm-name "console-compensation-failed" \
  --metric-name "saga.compensation.failed" \
  --namespace "Console/SagaCompensation" \
  --statistic Sum \
  --period 60 \
  --threshold 0 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 1 \
  --region $REGION 2>/dev/null || true

awslocal cloudwatch put-metric-alarm \
  --alarm-name "console-dead-letter-count" \
  --metric-name "compensation.dead_letter.count" \
  --namespace "Console/SagaCompensation" \
  --statistic Sum \
  --period 60 \
  --threshold 0 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 1 \
  --region $REGION 2>/dev/null || true

awslocal cloudwatch put-metric-alarm \
  --alarm-name "console-action-timeout" \
  --metric-name "action.execution.timeout" \
  --namespace "Console/ActionExecution" \
  --statistic Sum \
  --period 300 \
  --threshold 5 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 1 \
  --region $REGION 2>/dev/null || true

echo "  ✓ CloudWatch Alarms created"

# ─── SNS Topic for alerts (compensation dead letter, drift) ───
echo "→ Creating SNS Topic..."
awslocal sns create-topic --name console-alerts --region $REGION 2>/dev/null || true
echo "  ✓ SNS Topic: console-alerts"

echo ""
echo "════════════════════════════════════════════════════════════"
echo "  LocalStack Init: DONE ✅"
echo "  S3 endpoint:         http://localhost:4566"
echo "  CloudWatch endpoint: http://localhost:4566"
echo "  SNS endpoint:        http://localhost:4566"
echo "════════════════════════════════════════════════════════════"
