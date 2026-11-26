# Cheapest compliant network: two AZs, public + private subnets, NO NAT gateway,
# free S3 and DynamoDB gateway endpoints, NO interface endpoints.
# Fargate tasks and EMR run in the public subnets with public IPs and tight security groups.
# Neptune sits in the private subnets, which have no route to the internet at all.

data "aws_region" "current" {}

locals {
  # Required by the AWS-managed AmazonEMRServicePolicy_v2 (it only acts on resources carrying this tag).
  emr_tag = { "for-use-with-amazon-emr-managed-policies" = "true" }
}

# Justified exception AWS-0178 (accepted by the human): no VPC flow logs. Flow-log delivery to CloudWatch Logs bills data ingestion (about $0.50 per GB)
# and the course environment is a short-lived, budget-capped single VPC with no inbound except one listener; the cost was judged not worth it.
#trivy:ignore:AWS-0178
resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge({ Name = "${var.name}-vpc" }, local.emr_tag)
}

# The default security group is emptied so nothing can use it by accident.
resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name}-default-unused" }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name}-igw" }
}

# Justified exception AWS-0164: NAT-free design (INFRA-CONTRACTS section 2): Fargate tasks and EMR nodes in these subnets need a public IPv4 address to reach ECR, S3, DynamoDB, SQS, S3 Vectors and external APIs without a NAT gateway or interface endpoints; security groups allow no inbound from the internet except the ALB listener.
#trivy:ignore:AWS-0164
resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.this.id
  availability_zone       = var.azs[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index)
  map_public_ip_on_launch = true # EMR nodes need a public IP in a NAT-free design (no inbound is open).
  tags                    = merge({ Name = "${var.name}-public-${var.azs[count.index]}", tier = "public" }, local.emr_tag)
}

resource "aws_subnet" "private" {
  count                   = 2
  vpc_id                  = aws_vpc.this.id
  availability_zone       = var.azs[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index + 10)
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.name}-private-${var.azs[count.index]}", tier = "private" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name}-public" }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# Private route table: local route only (no NAT, no internet gateway route).
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name}-private" }
}

resource "aws_route_table_association" "private" {
  count          = 2
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# Free gateway endpoints (no hourly charge). Required by the Neptune bulk loader (S3) and used by DynamoDB clients.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id, aws_route_table.public.id]
  tags              = { Name = "${var.name}-s3-gateway" }
}

resource "aws_vpc_endpoint" "dynamodb" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.dynamodb"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id, aws_route_table.public.id]
  tags              = { Name = "${var.name}-dynamodb-gateway" }
}

############################ Security groups, chained by role ############################
# Internet -> alb -> query_api -> retrieval -> neptune ; emr -> neptune (bulk-load trigger).

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "Load balancer for query-api"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-alb" }
}

resource "aws_security_group" "query_api" {
  name        = "${var.name}-query-api"
  description = "query-api tasks"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-query-api" }
}

resource "aws_security_group" "retrieval" {
  name        = "${var.name}-retrieval"
  description = "retrieval tasks (internal)"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-retrieval" }
}

resource "aws_security_group" "embedding_worker" {
  name        = "${var.name}-embedding-worker"
  description = "embedding-worker tasks (SQS consumer, no inbound)"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-embedding-worker" }
}

resource "aws_security_group" "emr" {
  name        = "${var.name}-emr"
  description = "EMR cluster nodes (master and core/task)"
  vpc_id      = aws_vpc.this.id
  tags        = merge({ Name = "${var.name}-emr" }, local.emr_tag)
}

resource "aws_security_group" "neptune" {
  name        = "${var.name}-neptune"
  description = "Neptune cluster"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-neptune" }
}

# --- ALB: the single internet-facing listener ---
resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  for_each          = toset(var.alb_ingress_cidrs)
  security_group_id = aws_security_group.alb.id
  description       = "HTTP listener"
  cidr_ipv4         = each.value
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  for_each          = var.alb_https_enabled ? toset(var.alb_ingress_cidrs) : toset([])
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS listener"
  cidr_ipv4         = each.value
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_query_api" {
  security_group_id            = aws_security_group.alb.id
  description                  = "ALB to query-api"
  referenced_security_group_id = aws_security_group.query_api.id
  from_port                    = var.service_port
  to_port                      = var.service_port
  ip_protocol                  = "tcp"
}

# --- query-api ---
resource "aws_vpc_security_group_ingress_rule" "query_api_from_alb" {
  security_group_id            = aws_security_group.query_api.id
  description                  = "From the load balancer only"
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = var.service_port
  to_port                      = var.service_port
  ip_protocol                  = "tcp"
}

# Justified exception AWS-0104: NAT-free design (INFRA-CONTRACTS section 2): tasks in public subnets reach AWS service endpoints and the model APIs over the internet gateway; the destination set (AWS and external API IPs) is not enumerable, so egress is port-scoped to 443 and not CIDR-scoped. No inbound is open.
#trivy:ignore:AWS-0104
resource "aws_vpc_security_group_egress_rule" "query_api_https" {
  security_group_id = aws_security_group.query_api.id
  description       = "AWS APIs and external model APIs (HTTPS)"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "query_api_to_retrieval" {
  security_group_id            = aws_security_group.query_api.id
  description                  = "query-api to retrieval"
  referenced_security_group_id = aws_security_group.retrieval.id
  from_port                    = var.service_port
  to_port                      = var.service_port
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "query_api_to_neptune" {
  security_group_id            = aws_security_group.query_api.id
  description                  = "query-api to Neptune"
  referenced_security_group_id = aws_security_group.neptune.id
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  ip_protocol                  = "tcp"
}

# --- retrieval ---
resource "aws_vpc_security_group_ingress_rule" "retrieval_from_query_api" {
  security_group_id            = aws_security_group.retrieval.id
  description                  = "From query-api only"
  referenced_security_group_id = aws_security_group.query_api.id
  from_port                    = var.service_port
  to_port                      = var.service_port
  ip_protocol                  = "tcp"
}

# Justified exception AWS-0104: NAT-free design (INFRA-CONTRACTS section 2): tasks in public subnets reach AWS service endpoints and the model APIs over the internet gateway; the destination set (AWS and external API IPs) is not enumerable, so egress is port-scoped to 443 and not CIDR-scoped. No inbound is open.
#trivy:ignore:AWS-0104
resource "aws_vpc_security_group_egress_rule" "retrieval_https" {
  security_group_id = aws_security_group.retrieval.id
  description       = "AWS APIs and external model APIs (HTTPS)"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "retrieval_to_neptune" {
  security_group_id            = aws_security_group.retrieval.id
  description                  = "retrieval to Neptune"
  referenced_security_group_id = aws_security_group.neptune.id
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  ip_protocol                  = "tcp"
}

# --- embedding-worker (no inbound at all) ---
# Justified exception AWS-0104: NAT-free design (INFRA-CONTRACTS section 2): tasks in public subnets reach AWS service endpoints and the model APIs over the internet gateway; the destination set (AWS and external API IPs) is not enumerable, so egress is port-scoped to 443 and not CIDR-scoped. No inbound is open.
#trivy:ignore:AWS-0104
resource "aws_vpc_security_group_egress_rule" "embedding_worker_https" {
  security_group_id = aws_security_group.embedding_worker.id
  description       = "SQS, S3 Vectors, Bedrock, DynamoDB, external embedding API (HTTPS)"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

# --- EMR (public subnet, no inbound from the internet) ---
resource "aws_vpc_security_group_ingress_rule" "emr_self" {
  security_group_id            = aws_security_group.emr.id
  description                  = "Intra-cluster traffic"
  referenced_security_group_id = aws_security_group.emr.id
  ip_protocol                  = "-1"
}

resource "aws_vpc_security_group_egress_rule" "emr_self" {
  security_group_id            = aws_security_group.emr.id
  description                  = "Intra-cluster traffic"
  referenced_security_group_id = aws_security_group.emr.id
  ip_protocol                  = "-1"
}

# Justified exception AWS-0104: NAT-free design (INFRA-CONTRACTS section 2): tasks in public subnets reach AWS service endpoints and the model APIs over the internet gateway; the destination set (AWS and external API IPs) is not enumerable, so egress is port-scoped to 443 and not CIDR-scoped. No inbound is open.
#trivy:ignore:AWS-0104
resource "aws_vpc_security_group_egress_rule" "emr_https" {
  security_group_id = aws_security_group.emr.id
  description       = "S3, EMR, AWS APIs, package mirrors (HTTPS)"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

# Justified exception AWS-0104: NAT-free design (INFRA-CONTRACTS section 2): tasks in public subnets reach AWS service endpoints and the model APIs over the internet gateway; the destination set (AWS and external API IPs) is not enumerable, so egress is port-scoped to 80 (Amazon Linux package repositories) and not CIDR-scoped. No inbound is open.
#trivy:ignore:AWS-0104
resource "aws_vpc_security_group_egress_rule" "emr_http" {
  security_group_id = aws_security_group.emr.id
  description       = "Amazon Linux package repositories (HTTP)"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "emr_to_neptune" {
  security_group_id            = aws_security_group.emr.id
  description                  = "Neptune bulk-load trigger"
  referenced_security_group_id = aws_security_group.neptune.id
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  ip_protocol                  = "tcp"
}

# --- ops (one-off run-task for the Neptune bulk load and the embedding enqueue; no inbound at all) ---
resource "aws_security_group" "ops" {
  name        = "${var.name}-ops"
  description = "Ops one-off task (bulk load, enqueue): no inbound"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-ops" }
}

# Justified exception AWS-0104: same NAT-free reasoning as embedding_worker_https. The ops task reaches ECR, S3, DynamoDB, SQS and CloudWatch Logs
# over the internet gateway; the destinations are not enumerable, so egress is port-scoped to 443. No inbound is open.
#trivy:ignore:AWS-0104
resource "aws_vpc_security_group_egress_rule" "ops_https" {
  security_group_id = aws_security_group.ops.id
  description       = "ECR, S3, DynamoDB, SQS, CloudWatch Logs (HTTPS)"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "ops_to_neptune" {
  count                        = var.neptune_enabled ? 1 : 0
  security_group_id            = aws_security_group.ops.id
  description                  = "Neptune loader and status calls"
  referenced_security_group_id = aws_security_group.neptune.id
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "neptune_from_ops" {
  count                        = var.neptune_enabled ? 1 : 0
  security_group_id            = aws_security_group.neptune.id
  description                  = "ops one-off load task"
  referenced_security_group_id = aws_security_group.ops.id
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  ip_protocol                  = "tcp"
}

# --- Neptune: ingress only from the runtime roles that talk to it; egress only HTTPS to S3 (bulk loader) ---
resource "aws_vpc_security_group_ingress_rule" "neptune_from_query_api" {
  security_group_id            = aws_security_group.neptune.id
  description                  = "query-api"
  referenced_security_group_id = aws_security_group.query_api.id
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "neptune_from_retrieval" {
  security_group_id            = aws_security_group.neptune.id
  description                  = "retrieval"
  referenced_security_group_id = aws_security_group.retrieval.id
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "neptune_from_emr" {
  security_group_id            = aws_security_group.neptune.id
  description                  = "EMR load step"
  referenced_security_group_id = aws_security_group.emr.id
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  ip_protocol                  = "tcp"
}

# The Neptune bulk loader reads the load files from S3 through the S3 gateway endpoint. Terraform strips AWS's default
# allow-all egress, so this is the only egress Neptune has: TCP 443 to the S3 managed prefix list, nothing else.
resource "aws_vpc_security_group_egress_rule" "neptune_to_s3" {
  security_group_id = aws_security_group.neptune.id
  description       = "Neptune bulk loader to S3 via gateway endpoint"
  prefix_list_id    = aws_vpc_endpoint.s3.prefix_list_id
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}
