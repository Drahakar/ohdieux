package ca.ligature.ohdieux.actors.scraper.programme

import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import ca.ligature.ohdieux.ohdio.ApiClient
import ca.ligature.ohdieux.ohdio.RCModels
import ca.ligature.ohdieux.persistence._
import ca.ligature.ohdieux.actors.scraper.media.MediaScraperActor
import ca.ligature.ohdieux.actors.file.FileArchiveActor
import java.time.ZonedDateTime
import scala.collection.mutable

/**
 * Test suite for ProgrammeScraperActor focusing on the bug where
 * programmes stop being updated after transient failures.
 */
class ProgrammeScraperActorSpec
    extends ScalaTestWithActorTestKit
  with AnyWordSpecLike
    with Matchers {

  // Mock repositories with in-memory storage
  class MockProgrammeRepository extends ProgrammeRepository {
    val programmes = mutable.Map[Int, ProgrammeEntity]()

    override def getById(id: Int): Option[ProgrammeEntity] =
      programmes.get(id)

    override def save(programme: ProgrammeEntity): Unit =
      programmes(programme.id) = programme

    override def getAll(): Seq[ProgrammeEntity] =
      programmes.values.toSeq
  }

  class MockEpisodeRepository extends EpisodeRepository {
    val episodes = mutable.Map[Int, EpisodeEntity]()

    override def getById(id: Int): Option[EpisodeEntity] =
      episodes.get(id)

    override def getByProgrammeId(programmeId: Int): Seq[EpisodeEntity] =
      episodes.values.filter(_.programme_id == programmeId).toSeq

    override def save(episode: EpisodeEntity): Unit =
      episodes(episode.id) = episode

    override def countByProgrammeId(programmeId: Int): Int =
      episodes.values.count(_.programme_id == programmeId)
  }

  class MockProgrammeConfigRepository extends ProgrammeConfigRepository {
    val configs = mutable.Map[Int, ProgrammeConfigEntity]()

    override def getProgrammeConfig(programmeId: Int): ProgrammeConfigEntity =
      configs.getOrElse(
        programmeId,
        ProgrammeConfigEntity(programmeId, ProgrammeConfigStatus.Ok, "", "")
      )

    override def saveProgrammeConfig(config: ProgrammeConfigEntity): Unit =
      configs(config.programme_id) = config

    override def getAllByStatus(
        status: ProgrammeConfigStatus
    ): Seq[ProgrammeConfigEntity] =
      configs.values.filter(_.status == status).toSeq
  }

  // Mock ApiClient that can simulate failures
  class MockApiClient(
      var shouldFail: Boolean = false,
      var throwException: Boolean = false,
      var emptyFeed: Boolean = false,
      var replayOnly: Boolean = false
  ) extends ApiClient("http://mock", "test-agent") {
    var callCount = 0

    override def getProgrammeById(
        programmeType: ProgrammeType,
        programmeId: Int,
        pageNumber: Int
    ): RCModels.FetchResult[RCModels.ProgrammeById] = {
      callCount += 1
      if (throwException) {
        throw new RuntimeException("Simulated network failure")
      }

      if (shouldFail) {
        return RCModels.FetchResult.FetchFailure(
          "Simulated API failure (rate limit/timeout)"
        )
      }

      // Return minimal valid response
      val mockProgramme = createMockProgrammeById(programmeId, pageNumber)
      RCModels.FetchResult.Success(mockProgramme)
    }

    private def createMockProgrammeById(
        programmeId: Int,
        pageNumber: Int
    ): RCModels.ProgrammeById = {
      import RCModels._

      val now = ZonedDateTime.now()
      val episodeId = programmeId * 1000 + 1
      val items =
        if (pageNumber == 1 && !emptyFeed) {
          List(
            ProgrammeContentDetailItem(
              typename = "Episode",
              broadcastedFirstTimeAt = now.minusDays(1),
              duration = Duration(60),
              isBroadcastedReplay = replayOnly,
              playlistItemId = PlaylistItemId(
                None,
                PlaylistItemIdGlobalId2(
                  episodeId.toString,
                  PlaylistItemContentType(1)
                )
              ),
              summary = "Episode 1 description",
              title = "Episode 1",
              url = s"https://test.com/episode/$episodeId"
            )
          )
        } else {
          Nil
        }

      ProgrammeById(
        typename = "EmissionBalado",
        canonicalUrl = s"/programme/$programmeId",
        content = ProgrammeContent(
          typename = "ProgrammeContent",
          contentDetail = ProgrammeContentDetail(
            typename = "ProgrammeContentDetail",
            items = items,
            pagedConfiguration = PagedConfiguration(
              pageMaxLength = 20,
              pageNumber = pageNumber,
              pageSize = 20,
              totalNumberOfItems = 1
            )
          )
        ),
        header = ProgrammeHeader(
          typename = "ProgrammeHeader",
          title = s"Test Programme $programmeId",
          summary = "Test description",
          picture = ProgrammePicture(
            s"https://test.com/image/{width}/{ratio}.jpg"
          )
        )
      )
    }
  }

  // Create a no-op media scraper actor for testing
  def createNoOpMediaScraper(): ActorRef[MediaScraperActor.Message] =
    spawn(org.apache.pekko.actor.typed.scaladsl.Behaviors.ignore)

  // Create a no-op file archive actor for testing
  def createNoOpFileArchive(): ActorRef[FileArchiveActor.Message] =
    spawn(org.apache.pekko.actor.typed.scaladsl.Behaviors.ignore)

  def existingProgramme(programmeId: Int): ProgrammeEntity =
    ProgrammeEntity(
      id = programmeId,
      programme_type = ProgrammeType.Balado,
      title = "Existing Programme",
      description = "Test",
      author = "Radio-Canada",
      canonical_url = "https://test.com",
      image_url = "https://test.com/image.jpg",
      episodes = 0,
      last_checked = ""
    )

  "ProgrammeScraperActor" should {

    "mark programme as Failed when exception is thrown" in {
      val mockApi = new MockApiClient(throwException = true)
      val programmeRepo = new MockProgrammeRepository
      val episodeRepo = new MockEpisodeRepository
      val configRepo = new MockProgrammeConfigRepository
      val mediaScraper = createNoOpMediaScraper()
      val fileArchive = createNoOpFileArchive()

      val scraper = spawn(
        ProgrammeScraperActor(
          mockApi,
          programmeRepo,
          episodeRepo,
          configRepo,
          maxDepth = 100,
          mediaScraper,
          fileArchive,
          refreshInterval = 0 // Disable auto-refresh
        )
      )

      // Attempt to fetch programme - this should throw exception
      scraper ! ProgrammeScraperActor.Message.FetchProgramme(
        id = 12345,
        incremental = true
      )

      // Wait for async processing
      Thread.sleep(500)

      // Verify programme was marked as Failed
      val config = configRepo.getProgrammeConfig(12345)
      config.status shouldBe ProgrammeConfigStatus.Failed
      config.message should include("network failure")
    }

    "block refreshes only when a programme is manually suspended" in {
      val mockApi = new MockApiClient()
      val programmeRepo = new MockProgrammeRepository
      val episodeRepo = new MockEpisodeRepository
      val configRepo = new MockProgrammeConfigRepository
      val mediaScraper = createNoOpMediaScraper()
      val fileArchive = createNoOpFileArchive()

      // Pre-mark programme as manually suspended
      configRepo.saveProgrammeConfig(
        ProgrammeConfigEntity(
          12345,
          ProgrammeConfigStatus.Suspended,
          "Manually suspended",
          ZonedDateTime.now().toString
        )
      )

      val scraper = spawn(
        ProgrammeScraperActor(
          mockApi,
          programmeRepo,
          episodeRepo,
          configRepo,
          maxDepth = 100,
          mediaScraper,
          fileArchive,
          refreshInterval = 0
        )
      )

      // Try to fetch programme - suspension should block the API call
      scraper ! ProgrammeScraperActor.Message.FetchProgramme(
        id = 12345,
        incremental = true
      )

      Thread.sleep(500)

      // Verify the suspended programme was not fetched or saved
      mockApi.callCount shouldBe 0
      programmeRepo.getById(12345) shouldBe None
    }

    "retry FetchFailure and clear the status after a successful refresh" in {
      val mockApi = new MockApiClient(shouldFail = true)
      val programmeRepo = new MockProgrammeRepository
      val episodeRepo = new MockEpisodeRepository
      val configRepo = new MockProgrammeConfigRepository
      programmeRepo.save(existingProgramme(12345))
      val mediaScraper = createNoOpMediaScraper()
      val fileArchive = createNoOpFileArchive()

      val scraper = spawn(
        ProgrammeScraperActor(
          mockApi,
          programmeRepo,
          episodeRepo,
          configRepo,
          maxDepth = 100,
          mediaScraper,
          fileArchive,
          refreshInterval = 0
        )
      )

      // First attempt returns a retryable FetchFailure.
      scraper ! ProgrammeScraperActor.Message.FetchProgramme(
        id = 12345,
        incremental = true
      )

      Thread.sleep(500)

      val config1 = configRepo.getProgrammeConfig(12345)
      config1.status shouldBe ProgrammeConfigStatus.Failed
      config1.message should include("Simulated API failure")

      // Second attempt - API works now
      mockApi.shouldFail = false
      scraper ! ProgrammeScraperActor.Message.FetchProgramme(
        id = 12345,
        incremental = true
      )

      Thread.sleep(500)

      // A later success updates the programme and clears the transient failure.
      programmeRepo.getById(12345) shouldBe defined
      configRepo.getProgrammeConfig(12345).status shouldBe ProgrammeConfigStatus.Ok
      episodeRepo.countByProgrammeId(12345) shouldBe 1
    }

    "retry an exception failure on a later refresh" in {
      val mockApi = new MockApiClient(throwException = true)
      val programmeRepo = new MockProgrammeRepository
      val episodeRepo = new MockEpisodeRepository
      val configRepo = new MockProgrammeConfigRepository
      val mediaScraper = createNoOpMediaScraper()
      val fileArchive = createNoOpFileArchive()

      programmeRepo.save(existingProgramme(12345))

      val scraper = spawn(
        ProgrammeScraperActor(
          mockApi,
          programmeRepo,
          episodeRepo,
          configRepo,
          maxDepth = 100,
          mediaScraper,
          fileArchive,
          refreshInterval = 0
        )
      )

      // First auto-refresh attempt - throws exception
      scraper ! ProgrammeScraperActor.Message.FetchProgramme(
        id = 12345,
        incremental = true
      )

      Thread.sleep(500)

      // Verify marked as Failed
      val config1 = configRepo.getProgrammeConfig(12345)
      config1.status shouldBe ProgrammeConfigStatus.Failed

      // Fix the API (it's no longer failing)
      mockApi.throwException = false

      // The next refresh should retry and succeed.
      scraper ! ProgrammeScraperActor.Message.FetchProgramme(
        id = 12345,
        incremental = true
      )

      Thread.sleep(500)

      val config2 = configRepo.getProgrammeConfig(12345)
      config2.status shouldBe ProgrammeConfigStatus.Ok
      config2.message shouldBe ""

      episodeRepo.countByProgrammeId(12345) shouldBe 1
    }

    "accept empty and replay-only episode pages without failing" in {
      val mockApi = new MockApiClient(emptyFeed = true)
      val programmeRepo = new MockProgrammeRepository
      val episodeRepo = new MockEpisodeRepository
      val configRepo = new MockProgrammeConfigRepository
      val mediaScraper = createNoOpMediaScraper()
      val fileArchive = createNoOpFileArchive()

      val scraper = spawn(
        ProgrammeScraperActor(
          mockApi,
          programmeRepo,
          episodeRepo,
          configRepo,
          maxDepth = 100,
          mediaScraper,
          fileArchive,
          refreshInterval = 0
        )
      )

      scraper ! ProgrammeScraperActor.Message.FetchProgramme(12345)
      Thread.sleep(500)

      configRepo.getProgrammeConfig(12345).status shouldBe ProgrammeConfigStatus.Ok
      episodeRepo.countByProgrammeId(12345) shouldBe 0

      mockApi.emptyFeed = false
      mockApi.replayOnly = true
      scraper ! ProgrammeScraperActor.Message.FetchProgramme(12346)
      Thread.sleep(500)

      configRepo.getProgrammeConfig(12346).status shouldBe ProgrammeConfigStatus.Ok
      episodeRepo.countByProgrammeId(12346) shouldBe 1
    }
  }
}
