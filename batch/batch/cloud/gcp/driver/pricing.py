import logging
from typing import AsyncGenerator, List, Optional

import dateutil.parser

from hailtop.aiocloud import aiogoogle
from hailtop.utils import rate_cpu_hour_to_mcpu_msec, rate_gib_hour_to_mib_msec, rate_gib_month_to_mib_msec

from ....driver.pricing import Price
from ..resources import (
    GCPComputeResource,
    GCPLocalSSDStaticSizedDiskResource,
    GCPMemoryResource,
    GCPStaticSizedDiskResource,
)

log = logging.getLogger('pricing')


class GCPComputePrice(Price):
    def __init__(
        self,
        instance_family: str,
        preemptible: bool,
        region: str,
        cost_per_hour: float,
        effective_start_date: int,
        effective_end_date: Optional[int] = None,
    ):
        self.instance_family = instance_family
        self.preemptible = preemptible
        self.region = region
        self.cost_per_hour = cost_per_hour
        self.effective_start_date = effective_start_date
        self.effective_end_date = effective_end_date

    @property
    def product(self):
        return GCPComputeResource.product_name(self.instance_family, self.preemptible, self.region)

    @property
    def rate(self):
        return rate_cpu_hour_to_mcpu_msec(self.cost_per_hour)


class GCPMemoryPrice(Price):
    def __init__(
        self,
        instance_family: str,
        preemptible: bool,
        region: str,
        cost_per_hour: float,
        effective_start_date: int,
        effective_end_date: Optional[int] = None,
    ):
        self.instance_family = instance_family
        self.preemptible = preemptible
        self.region = region
        self.cost_per_hour = cost_per_hour
        self.effective_start_date = effective_start_date
        self.effective_end_date = effective_end_date

    @property
    def product(self):
        return GCPMemoryResource.product_name(self.instance_family, self.preemptible, self.region)

    @property
    def rate(self):
        return rate_gib_hour_to_mib_msec(self.cost_per_hour)


class GCPLocalSSDDiskPrice(Price):
    def __init__(
        self,
        preemptible: bool,
        region: str,
        cost_per_month: float,
        effective_start_date: int,
        effective_end_date: Optional[int] = None,
    ):
        self.preemptible = preemptible
        self.region = region
        self.cost_per_month = cost_per_month
        self.effective_start_date = effective_start_date
        self.effective_end_date = effective_end_date

    @property
    def cost_per_gib_month(self):
        return self.cost_per_month

    @property
    def product(self):
        return GCPLocalSSDStaticSizedDiskResource.product_name(self.preemptible, self.region)

    @property
    def rate(self):
        return rate_gib_month_to_mib_msec(self.cost_per_gib_month)


class GCPDiskPrice(Price):
    def __init__(
        self,
        disk_type: str,
        region: str,
        cost_per_month: float,
        effective_start_date: int,
        effective_end_date: Optional[int] = None,
    ):
        self.disk_type = disk_type
        self.region = region
        self.cost_per_month = cost_per_month
        self.effective_start_date = effective_start_date
        self.effective_end_date = effective_end_date

    @property
    def cost_per_gib_month(self):
        return self.cost_per_month

    @property
    def product(self):
        return GCPStaticSizedDiskResource.product_name(self.disk_type, self.region)

    @property
    def rate(self):
        return rate_gib_month_to_mib_msec(self.cost_per_gib_month)


def parse_effective_start_date(sku: dict) -> int:
    return int(dateutil.parser.isoparse(sku['pricingInfo'][0]['effectiveTime']).timestamp() * 1000 + 0.5)


def pricing_expression_to_price_per_unit(pricing_expression: dict) -> float:
    # https://cloud.google.com/billing/docs/reference/rest/v1/services.skus/list
    # https://cloud.google.com/billing/docs/reference/rest/v1/services.skus/list#Money
    assert len(pricing_expression['tieredRates']) == 1, str(pricing_expression)
    unit_price = pricing_expression['tieredRates'][0]['unitPrice']
    assert unit_price['currencyCode'] == 'USD', str(pricing_expression)
    return int(unit_price['units']) + (unit_price['nanos'] / 1000 / 1000 / 1000)


def instance_family_from_sku(sku: dict) -> Optional[str]:
    category = sku['category']
    if category['resourceGroup'] == 'N1Standard':
        return 'n1'
    return None


def preemptible_from_sku(sku: dict) -> Optional[bool]:
    category = sku['category']
    usageType = category['usageType']

    if usageType == 'OnDemand':
        return False
    if usageType == 'Preemptible':
        return True
    return None


def process_compute_sku(sku: dict, regions: List[str]) -> List[GCPComputePrice]:
    category = sku['category']
    assert category['resourceFamily'] == 'Compute', sku
    assert 'Core' in sku['description']

    instance_family = instance_family_from_sku(sku)
    preemptible = preemptible_from_sku(sku)

    if instance_family is None or preemptible is None:
        return []

    effective_start_date = parse_effective_start_date(sku)

    # https://cloud.google.com/billing/docs/reference/rest/v1/services.skus/list#sku
    pricing_info = sku['pricingInfo'][-1]  # A timeline of pricing info for this SKU in chronological order.
    pricing_expression = pricing_info['pricingExpression']
    assert (
        pricing_expression['usageUnitDescription'] == 'hour'
        and pricing_expression['baseUnit'] == 's'
        and pricing_expression['baseUnitConversionFactor'] == 3600
    ), sku
    cost_per_hour = pricing_expression_to_price_per_unit(pricing_expression)

    compute_prices = []
    service_regions = sku['serviceRegions']
    for service_region in service_regions:
        if service_region in regions:
            compute_prices.append(
                GCPComputePrice(instance_family, preemptible, service_region, cost_per_hour, effective_start_date)
            )
    return compute_prices


def process_memory_sku(sku: dict, regions: List[str]) -> List[GCPMemoryPrice]:
    category = sku['category']
    assert category['resourceFamily'] == 'Compute', sku
    assert 'Ram' in sku['description']

    instance_family = instance_family_from_sku(sku)
    preemptible = preemptible_from_sku(sku)

    if instance_family is None or preemptible is None:
        return []

    effective_start_date = parse_effective_start_date(sku)

    # https://cloud.google.com/billing/docs/reference/rest/v1/services.skus/list#sku
    pricing_info = sku['pricingInfo'][-1]  # A timeline of pricing info for this SKU in chronological order.
    pricing_expression = pricing_info['pricingExpression']
    assert pricing_expression['usageUnit'] == 'GiBy.h', sku
    cost_per_hour = pricing_expression_to_price_per_unit(pricing_expression)

    memory_prices = []
    service_regions = sku['serviceRegions']
    for service_region in service_regions:
        if service_region in regions:
            memory_prices.append(
                GCPMemoryPrice(instance_family, preemptible, service_region, cost_per_hour, effective_start_date)
            )
    return memory_prices


def process_local_ssd_sku(sku: dict, regions: List[str]) -> List[GCPLocalSSDDiskPrice]:
    category = sku['category']
    assert category['resourceFamily'] == 'Storage', sku
    assert category['resourceGroup'] == 'LocalSSD', sku

    preemptible = preemptible_from_sku(sku)
    if preemptible is None:
        return []

    effective_start_date = parse_effective_start_date(sku)

    # https://cloud.google.com/billing/docs/reference/rest/v1/services.skus/list#sku
    pricing_info = sku['pricingInfo'][-1]  # A timeline of pricing info for this SKU in chronological order.
    pricing_expression = pricing_info['pricingExpression']
    assert pricing_expression['usageUnit'] == 'GiBy.mo', sku
    cost_per_month = pricing_expression_to_price_per_unit(pricing_expression)

    local_ssd_prices = []
    service_regions = sku['serviceRegions']
    for service_region in service_regions:
        if service_region in regions:
            local_ssd_prices.append(
                GCPLocalSSDDiskPrice(preemptible, service_region, cost_per_month, effective_start_date)
            )
    return local_ssd_prices


def process_disk_sku(sku: dict, regions: List[str]) -> List[GCPDiskPrice]:
    category = sku['category']
    assert category['resourceFamily'] == 'Storage', sku
    assert category['resourceGroup'] == 'SSD', sku
    assert 'SSD backed PD Capacity' in sku['description'], sku
    assert 'Regional' not in sku['description'], sku

    effective_start_date = parse_effective_start_date(sku)

    # https://cloud.google.com/billing/docs/reference/rest/v1/services.skus/list#sku
    pricing_info = sku['pricingInfo'][-1]  # A timeline of pricing info for this SKU in chronological order.
    pricing_expression = pricing_info['pricingExpression']
    assert pricing_expression['usageUnit'] == 'GiBy.mo', sku
    cost_per_month = pricing_expression_to_price_per_unit(pricing_expression)

    disk_prices = []
    service_regions = sku['serviceRegions']
    for service_region in service_regions:
        if service_region in regions:
            disk_prices.append(GCPDiskPrice('pd-ssd', service_region, cost_per_month, effective_start_date))
    return disk_prices


async def fetch_prices(
    billing_client: aiogoogle.GoogleBillingClient, regions: List[str], currency_code: str
) -> AsyncGenerator[Price, None]:
    params = {'currencyCode': currency_code}
    async for sku in billing_client.list_skus('/6F81-5844-456A/skus', params=params):
        category = sku['category']
        if category['resourceFamily'] == 'Compute':
            if 'Core' in sku['description']:
                for compute_price in process_compute_sku(sku, regions):
                    yield compute_price
            elif 'Ram' in sku['description']:
                for memory_price in process_memory_sku(sku, regions):
                    yield memory_price
        elif category['resourceFamily'] == 'Storage':
            if category['resourceGroup'] == 'LocalSSD':
                for local_ssd_price in process_local_ssd_sku(sku, regions):
                    yield local_ssd_price
            elif (
                category['resourceGroup'] == 'SSD'
                and 'SSD backed PD Capacity' in sku['description']
                and 'Regional' not in sku['description']
            ):
                for disk_price in process_disk_sku(sku, regions):
                    yield disk_price
