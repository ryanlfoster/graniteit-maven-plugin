/*
 * This is free and unencumbered software released into the public domain.
 *
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 *
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the
 * software to the public domain. We make this dedication for the benefit
 * of the public at large and to the detriment of our heirs and
 * successors. We intend this dedication to be an overt act of
 * relinquishment in perpetuity of all present and future rights to this
 * software under copyright law.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 * For more information, please refer to <http://unlicense.org/>
 */

package net.adamcin.graniteit

import dispatch._, dispatch.Defaults._
import com.ning.http.client._
import org.apache.maven.plugins.annotations.{Component, Parameter}
import scala.Some
import annotation.tailrec
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher
import net.adamcin.graniteit.mojo.BaseMojo
import scala.concurrent.Future

/**
 * Adds fluid support for the MKCOL method
 * @since 0.8.0
 */
trait DavVerbs extends MethodVerbs {
  def MKCOL = subject.setMethod("MKCOL")
}

/**
 * Wraps an implicitly created DefaultRequestVerbs object with the DavVerbs trait
 * @param wrapped the Req to wrap
 */
class DavReq(wrapped: Req) extends Req(wrapped.run) with DavVerbs

/**
 * Trait defining common mojo parameters and methods for establishing HTTP connections to a Granite server.
 * @since 0.8.0
 */
trait HttpParameters extends BaseMojo {

  final val DEFAULT_BASE_URL = "http://localhost:4502"
  final val DEFAULT_USER = "admin"
  final val DEFAULT_PASS = "admin"
  final val DEFAULT_PROXY_PROTOCOL = "http"
  final val DEFAULT_PROXY_HOST = "localhost"

  @Component
  var securityDispatcher: SecDispatcher = null

  /**
   * Specify the CQ username associated with package creation and installation
   */
  @Parameter(property = "graniteit.username", alias = "user", defaultValue = DEFAULT_USER)
  val username = DEFAULT_USER

  /**
   * Password to use in connection credentials
   */
  @Parameter(property = "graniteit.password", alias = "pass", defaultValue = DEFAULT_PASS)
  val password = DEFAULT_PASS

  /**
   * Id of server defined in the maven settings to use for credentials
   */
  @Parameter(property = "graniteit.serverId")
  val serverId: String = null

  /**
   * Server base URL (including context path, but without trailing slash)
   */
  @Parameter(property = "graniteit.baseUrl", defaultValue = DEFAULT_BASE_URL)
  val baseUrl: String = DEFAULT_BASE_URL

  /**
   * Set to true to completely disable HTTP proxy connections for this plugin.
   * Overrides any other HTTP proxy configuration
   */
  @Parameter(property = "graniteit.proxy.noProxy")
  val noProxy = false

  /**
   * Set to true to override the proxy configuration in the user's Maven Settings with the
   * associated mojo parameter alternatives
   */
  @Parameter(property = "graniteit.proxy.set")
  val proxySet = false

  /**
   * The HTTP Proxy protocol
   */
  @Parameter(property = "graniteit.proxy.protocol", defaultValue = DEFAULT_PROXY_PROTOCOL)
  val proxyProtocol: String = DEFAULT_PROXY_PROTOCOL

  /**
   * The HTTP Proxy hostname
   */
  @Parameter(property = "graniteit.proxy.host", defaultValue = DEFAULT_PROXY_HOST)
  val proxyHost: String = DEFAULT_PROXY_HOST

  /**
   * The HTTP Proxy port. Set to -1 to use the default proxy port.
   */
  @Parameter(property = "graniteit.proxy.port")
  val proxyPort: Int = -1

  /**
   * The HTTP Proxy username
   */
  @Parameter(property = "graniteit.proxy.username")
  val proxyUsername: String = null

  /**
   * The HTTP Proxy password
   */
  @Parameter(property = "graniteit.proxy.password")
  val proxyPassword: String = null

  /**
   * Server ID for proxy credentials defined in maven settings
   */
  @Parameter(property = "graniteit.proxy.serverId")
  val proxyServerId: String = null

  /**
   * Set to true to skip the use of the MKCOL WebDAV method for the creation ancestor JCR paths
   */
  @Parameter(property = "graniteit.skip.mkdirs")
  var skipMkdirs = false

  lazy val credentials: (String, String) = {
    val exCreds = (username, optDecrypt(password))

    Option(serverId) match {
      case None => exCreds
      case Some(id) => Option(settings.getServer(id)) match {
        case None => getLog.warn("Failed to find server with id " + id); exCreds
        case Some(server) => (server.getUsername, optDecrypt(server.getPassword))
      }
    }
  }

  lazy val proxyCredentials: (String, String) = {
    val exCreds = (proxyUsername, optDecrypt(proxyPassword))

    Option(proxyServerId) match {
      case None => exCreds
      case Some(id) => Option(settings.getServer(id)) match {
        case None => getLog.warn("Failed to find server with id " + id); exCreds
        case Some(server) => (server.getUsername, optDecrypt(server.getPassword))
      }
    }
  }

  implicit def implyDavVerbs(req: Req) = new DavReq(req)

  def urlForPath(absPath: String): Req = {
    val (u, p) = credentials
    List(url(baseUrl + absPath) as_!(u, p)).map {
      (req) => activeProxy match {
        case None => req
        case Some(proxy) => req.setProxyServer(proxy)
      }
    }.head
  }

  def optDecrypt(password: String): String = Option(password) match {
    case None => password
    case Some(p) => securityDispatcher.decrypt(p)
  }

  lazy val activeProxy: Option[ProxyServer] = {
    val (u, p) = proxyCredentials
    if (noProxy) {
      None
    } else if (proxySet) {
      val proxyServer =
        new ProxyServer(ProxyServer.Protocol.valueOf(Option(proxyProtocol).getOrElse("HTTP")), proxyHost, proxyPort, u, p)
      Some(proxyServer)
    } else {
      Option(settings.getActiveProxy) match {
        case None => None
        case Some(proxy) => {
          val proxyServer =
            new ProxyServer(
              ProxyServer.Protocol.valueOf(Option(proxy.getProtocol).getOrElse("HTTP")),
              proxy.getHost,
              proxy.getPort,
              proxy.getUsername,
              optDecrypt(proxy.getPassword))

          Option(proxy.getNonProxyHosts) match {
            case None => ()
            case Some(nonProxyHosts) => {
              nonProxyHosts.split("\\|").foreach { proxyServer.addNonProxyHost(_) }
            }
          }

          Option(proxyServer)
        }
      }
      None
    }
  }

  def isSuccess(req: Req, resp: Response): Boolean = {
    (req.toRequest.getMethod, Option(resp)) match {
      case ("MKCOL", Some(response)) => {
        Set(201, 405) contains response.getStatusCode
      }
      case ("PUT", Some(response)) => {
        Set(201, 204) contains response.getStatusCode
      }
      case (_, Some(response)) => {
        Set(200) contains response.getStatusCode
      }
      case _ => false
    }
  }

  def isSlingPostSuccess(req: Req, resp: Response): Boolean = {
    (req.toRequest.getMethod, Option(resp)) match {
      case ("POST", Some(response)) => {
        Set(200, 201) contains response.getStatusCode
      }
      case _ => false
    }
  }

  def getReqRespLogMessage(req: Req, resp: Response): String = {
    (Option(req), Option(resp)) match {
      case (Some(request), Some(response)) =>
        request.toRequest.getMethod + " " + request.url + " => " + resp.getStatusCode + " " + resp.getStatusText
      case (Some(request), None) =>
        request.toRequest.getMethod + " " + request.url + " => null"
      case (None, Some(response)) =>
        "null => " + resp.getStatusCode + " " + resp.getStatusText
      case _ => "null => null"
    }
  }

  @tailrec
  final def waitForResponse[T](nTrys: Int)
                              (implicit until: Long,
                               requestFunction: () => (Request, AsyncHandler[T]),
                               contentChecker: (Future[T]) => Future[Boolean]): Boolean = {
    if (nTrys > 0) {
      val sleepTime = nTrys * 1000L
      getLog.info("sleeping " + nTrys + " seconds")
      Thread.sleep(sleepTime)
    }
    val mayProceed = contentChecker(for (res <- Http(requestFunction())) yield res)
    if (mayProceed()) {
      true
    } else {
      if (System.currentTimeMillis() >= until) {
        false
      } else {
        waitForResponse(nTrys + 1)
      }
    }
  }

  def mkdirs(absPath: String): (Req, Response) = {
    val segments = absPath.split('/').filter(!_.isEmpty)

    val dirs = segments.foldLeft(List.empty[String]) {
      (dirs: List[String], segment: String) => dirs match {
        case Nil => List("/" + segment)
        case head :: tail => (head + "/" + segment) :: dirs
      }
    }.reverse

    dirs.foldLeft (null: (Req, Response)) {
      (p: (Req, Response), path: String) => {
        val doMkdir = Option(p) match {
          case Some((req, resp)) => isSuccess(req, resp)
          case None => true
        }
        if (doMkdir) { mkdir(path) } else { p }
      }
    }
  }

  def mkdir(absPath: String): (Req, Response) = {
    val req: Req = urlForPath(absPath).MKCOL
    expedite(req, Http(req))
  }

  def expedite[T](req: Req, resp: Future[T]): (Req, T) = (req, resp())
}